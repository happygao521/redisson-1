/**
 * Copyright 2014 Nikita Koksharov, Nickolay Borbit
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.redisson;

import io.netty.util.Timeout;
import io.netty.util.TimerTask;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.Promise;
import io.netty.util.internal.PlatformDependent;
import org.redisson.client.BaseRedisPubSubListener;
import org.redisson.client.RedisPubSubListener;
import org.redisson.client.protocol.RedisCommands;
import org.redisson.client.protocol.pubsub.PubSubType;
import org.redisson.core.RLock;

import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;

/**
 * Distributed implementation of {@link java.util.concurrent.locks.Lock}
 * Implements reentrant lock.<br>
 * Lock will be removed automatically if client disconnects.
 *
 * {@link java.util.concurrent.locks.Lock}的分布式实现, 实现了可重入锁
 *
 * 客户端断开连接的时候会自动移除锁
 *
 * @author Nikita Koksharov
 *
 */
public class RedissonLock extends RedissonExpirable implements RLock {

    // 默认的锁过期时间为30s
    public static final long LOCK_EXPIRATION_INTERVAL_SECONDS = 30;
    //使用netty,创建平台独立的类实现
    private static final ConcurrentMap<String, Timeout> refreshTaskMap = PlatformDependent.newConcurrentHashMap();
    // 内部锁占用时间为30s
    protected long internalLockLeaseTime = TimeUnit.SECONDS.toMillis(LOCK_EXPIRATION_INTERVAL_SECONDS);

    private final UUID id;

    private static final Integer unlockMessage = 0;

    private static final ConcurrentMap<String, RedissonLockEntry> ENTRIES = PlatformDependent.newConcurrentHashMap();

    protected RedissonLock(CommandExecutor commandExecutor, String name, UUID id) {
        super(commandExecutor, name);
        this.id = id;
    }

    private void unsubscribe() {
        while (true) {
            RedissonLockEntry entry = ENTRIES.get(getEntryName());
            if (entry == null) {
                return;
            }

            RedissonLockEntry newEntry = new RedissonLockEntry(entry);
            newEntry.release();
            if (ENTRIES.replace(getEntryName(), entry, newEntry)) {
                if (newEntry.isFree()
                        && ENTRIES.remove(getEntryName(), newEntry)) {
                    synchronized (ENTRIES) {
                        // maybe added during subscription
                        if (!ENTRIES.containsKey(getEntryName())) {
                            commandExecutor.getConnectionManager().unsubscribe(getChannelName());
                        }
                    }
                }
                return;
            }
        }
    }

    private String getEntryName() {
        return id + ":" + getName();
    }

    private Promise<Boolean> aquire() {
        while (true) {
            RedissonLockEntry entry = ENTRIES.get(getEntryName());
            if (entry == null) {
                return null;
            }

            RedissonLockEntry newEntry = new RedissonLockEntry(entry);
            newEntry.aquire();
            if (ENTRIES.replace(getEntryName(), entry, newEntry)) {
                return newEntry.getPromise();
            }
        }
    }

    //利用redis的pubsub提供一个通知机制来减少不断的重试.
    private Future<Boolean> subscribe() {
        Promise<Boolean> promise = aquire();
        if (promise != null) {
            return promise;
        }

        Promise<Boolean> newPromise = newPromise();
        final RedissonLockEntry value = new RedissonLockEntry(newPromise);
        value.aquire();
        RedissonLockEntry oldValue = ENTRIES.putIfAbsent(getEntryName(), value);
        if (oldValue != null) {
            Promise<Boolean> oldPromise = aquire();
            if (oldPromise == null) {
                return subscribe();
            }
            return oldPromise;
        }

        RedisPubSubListener<Integer> listener = new BaseRedisPubSubListener<Integer>() {

            @Override
            public void onMessage(String channel, Integer message) {
                if (message.equals(unlockMessage) && getChannelName().equals(channel)) {
                    value.getLatch().release();
                }
            }

            @Override
            public boolean onStatus(PubSubType type, String channel) {
                if (channel.equals(getChannelName()) && !value.getPromise().isSuccess()) {
                    value.getPromise().setSuccess(true);
                    return true;
                }
                return false;
            }

        };

        synchronized (ENTRIES) {
            commandExecutor.getConnectionManager().subscribe(listener, getChannelName());
        }
        return newPromise;
    }

    private String getChannelName() {
        return "redisson__lock__channel__{" + getName() + "}";
    }

    @Override
    public void lock() {
        try {
            lockInterruptibly();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void lock(long leaseTime, TimeUnit unit) {
        try {
            lockInterruptibly(leaseTime, unit);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }


    @Override
    public void lockInterruptibly() throws InterruptedException {
        lockInterruptibly(-1, null);
    }

    @Override
    public void lockInterruptibly(long leaseTime, TimeUnit unit) throws InterruptedException {
        Long ttl;
        if (leaseTime != -1) {
            ttl = tryLockInner(leaseTime, unit);
        } else {
            ttl = tryLockInner();
        }
        // lock acquired
        if (ttl == null) {
            return;
        }

        subscribe().awaitUninterruptibly();

        try {
            while (true) {
                if (leaseTime != -1) {
                    ttl = tryLockInner(leaseTime, unit);
                } else {
                    ttl = tryLockInner();
                }
                // lock acquired
                if (ttl == null) {
                    break;
                }

                // waiting for message
                RedissonLockEntry entry = ENTRIES.get(getEntryName());
                if (ttl >= 0) {
                    entry.getLatch().tryAcquire(ttl, TimeUnit.MILLISECONDS);
                } else {
                    entry.getLatch().acquire();
                }
            }
        } finally {
            unsubscribe();
        }
    }

    @Override
    public boolean tryLock() {
        return tryLockInner() == null;
    }

    // 返回null标识获取到锁
    private Long tryLockInner() {
        Long ttlRemaining = tryLockInner(LOCK_EXPIRATION_INTERVAL_SECONDS, TimeUnit.SECONDS);
        // lock acquired
        if (ttlRemaining == null) {
            newRefreshTask();
        }
        return ttlRemaining;
    }

    // 好像是刷新client的连接的超时时间,防止client超时断开连接
    private void newRefreshTask() {
        if (refreshTaskMap.containsKey(getName())) {
            return;
        }

        Timeout task = commandExecutor.getConnectionManager().newTimeout(new TimerTask() {
            @Override
            public void run(Timeout timeout) throws Exception {
                expire(internalLockLeaseTime, TimeUnit.MILLISECONDS);
                refreshTaskMap.remove(getName());
                newRefreshTask(); // reschedule itself
            }
        }, internalLockLeaseTime / 3, TimeUnit.MILLISECONDS);

        if (refreshTaskMap.putIfAbsent(getName(), task) != null) {
            task.cancel();
        }
    }

    /**
     * Stop refresh timer
     * @return true if timer was stopped successfully
     */
    private void stopRefreshTask() {
        Timeout task = refreshTaskMap.remove(getName());
        if (task != null) {
            task.cancel();
        }
    }


    /**
     * 返回null说明已经获得锁
     * @param leaseTime
     * @param unit
     * @return
     */
    private Long tryLockInner(final long leaseTime, final TimeUnit unit) {
        internalLockLeaseTime = unit.toMillis(leaseTime);

        // lua脚本 {@link http://redis.readthedocs.org/en/latest/script/eval.html}
        // Redis 也保证脚本会以原子性(atomic)的方式执行
        //其中['o']是一个唯一的值,由UUID和线程id组成, ['c']用来标识当前线程持有当前锁的数量
        return commandExecutor.evalWrite(getName(), RedisCommands.EVAL_INTEGER,
                "local v = redis.call('get', KEYS[1]); " +
                                "if (v == false) then " +
                                "  redis.call('set', KEYS[1], cjson.encode({['o'] = ARGV[1], ['c'] = 1}), 'px', ARGV[2]); " + // 设置set key value px argv[2]
                                "  return nil; " +
                                "else " +
                                "  local o = cjson.decode(v); " +
                                "  if (o['o'] == ARGV[1]) then " + // 当前线程重复获得锁
                                "    o['c'] = o['c'] + 1; redis.call('set', KEYS[1], cjson.encode(o), 'px', ARGV[2]); " + // 增加当前线程持有这个锁的数量
                                "    return nil; " +
                                "  end;" +
                                "  return redis.call('pttl', KEYS[1]); " +  //PTTL这个命令类似于 TTL 命令，但它以毫秒为单位返回 key 的剩余生存时间，而不是像 TTL 命令那样，以秒为单位。

                        "end",
                        Collections.<Object>singletonList(getName()) , // keys
                id.toString() + "-" + Thread.currentThread().getId(), internalLockLeaseTime); // argvs
    }

    public boolean tryLock(long waitTime, long leaseTime, TimeUnit unit) throws InterruptedException {
        long time = unit.toMillis(waitTime);
        Long ttl;
        if (leaseTime != -1) {
            ttl = tryLockInner(leaseTime, unit);
        } else {
            ttl = tryLockInner();
        }
        // lock acquired
        if (ttl == null) {
            return true;
        }

        //subscribe这个方法代码有点多, Redisson通过netty来和redis通讯, 然后subscribe返回的是一个Future类型,
        //Future的awaitUninterruptibly()调用会阻塞, 然后Redisson通过Redis的pubsub来监听unlock的topic(getChannelName())
        //当解锁时, 会向名为 getChannelName() 的topic来发送解锁消息("0")
        //而这里 subscribe() 中监听这个topic, 在订阅成功时就会唤醒阻塞在awaitUninterruptibly()的方法.
        //所以线程在这里只会阻塞很短的时间(订阅成功即唤醒, 并不代表已经解锁)
        //利用redis的pubsub提供一个通知机制来减少不断的重试.
        // 很多的Redis锁实现都是失败后sleep一定时间后重试, 在锁被占用时间较长时,
        // 不断的重试是浪费, 而sleep也会导致不必要的时间浪费(在sleep期间可能已经解锁了), sleep时间太长, 时间浪费, 太短, 重试次数会增加~~~.
        if (!subscribe().awaitUninterruptibly(time, TimeUnit.MILLISECONDS)) {
            return false;
        }

        try {
            while (true) {
                if (leaseTime != -1) {
                    ttl = tryLockInner(leaseTime, unit);
                } else {
                    ttl = tryLockInner();
                }
                // lock acquired
                if (ttl == null) {
                    break;
                }

                if (time <= 0) {
                    return false;
                }

                // waiting for message
                long current = System.currentTimeMillis();

                // 这里才是真正的等待解锁消息, 收到解锁消息, 就唤醒, 然后尝试获取锁, 成功返回, 失败则阻塞在tryAcquire().
                // 收到订阅成功消息, 则唤醒阻塞上面的subscribe().awaitUninterruptibly();
                // 收到解锁消息, 则唤醒阻塞在下面的entry.getLatch().tryAcquire();
                RedissonLockEntry entry = ENTRIES.get(getEntryName());
                if (ttl >= 0 && ttl < time) {
                    // 使用锁的生存时间和锁持有时间中的最小值
                    entry.getLatch().tryAcquire(ttl, TimeUnit.MILLISECONDS);
                } else {
                    entry.getLatch().tryAcquire(time, TimeUnit.MILLISECONDS);
                }

                long elapsed = System.currentTimeMillis() - current;
                time -= elapsed;
            }
            return true;
        } finally {
            ///加锁成功或异常,解除订阅
            unsubscribe();
        }
    }

    @Override
    public boolean tryLock(long time, TimeUnit unit) throws InterruptedException {
        return tryLock(time, -1, unit);
    }

    @Override
    public void unlock() {
        Boolean opStatus = commandExecutor.evalWrite(getName(), RedisCommands.EVAL_BOOLEAN,
                "local v = redis.call('get', KEYS[1]); " +
                                "if (v == false) then " +  // 锁已经释放
                                "  redis.call('publish', ARGV[4], ARGV[2]); " + // 对外通知
                                "  return true; " +
                                "else " +
                                "  local o = cjson.decode(v); " +
                                "  if (o['o'] == ARGV[1]) then " + // 判断是否是锁的持有线程来释放锁
                                "    o['c'] = o['c'] - 1; " +  // 减少线程持有锁的次数
                                "    if (o['c'] > 0) then " + // 如果线程依旧持有锁
                                "      redis.call('set', KEYS[1], cjson.encode(o), 'px', ARGV[3]); " + // 设置锁的过期时间为30s
                                "      return false;"+ // 锁释放失败
                                "    else " +
                                "      redis.call('del', KEYS[1]);" + // 如果线程已经不在持有锁了则删除key
                                "      redis.call('publish', ARGV[4], ARGV[2]); " + // 对外通知
                                "      return true;"+
                                "    end" +
                                "  end;" +
                                "  return nil; " +
                                "end",
                        Collections.<Object>singletonList(getName()),
                id.toString() + "-" + Thread.currentThread().getId(), unlockMessage, internalLockLeaseTime, getChannelName());
        if (opStatus == null) {
            throw new IllegalStateException("Can't unlock lock Current id: "
                    + id + " thread-id: " + Thread.currentThread().getId());
        }
        if (opStatus) {
            stopRefreshTask();
        }
    }

    @Override
    public Condition newCondition() {
        // TODO implement
        throw new UnsupportedOperationException();
    }

    @Override
    public void forceUnlock() {
        get(forceUnlockAsync());
    }

    private Future<Boolean> forceUnlockAsync() {
        stopRefreshTask();
        return commandExecutor.evalWriteAsync(getName(), RedisCommands.EVAL_BOOLEAN,
                "redis.call('del', KEYS[1]); redis.call('publish', ARGV[2], ARGV[1]); return true",
                        Collections.<Object>singletonList(getName()), unlockMessage, getChannelName());
    }

    @Override
    public boolean isLocked() {
        return commandExecutor.read(getName(), RedisCommands.EXISTS, getName());
    }

    @Override
    public boolean isHeldByCurrentThread() {
        Boolean opStatus = commandExecutor.evalRead(getName(), RedisCommands.EVAL_BOOLEAN,
                            "local v = redis.call('get', KEYS[1]); " +
                                "if (v == false) then " +
                                "  return false; " +
                                "else " +
                                "  local o = cjson.decode(v); " +
                                "  if (o['o'] == ARGV[1]) then " +
                                "    return true; " +
                                "  else" +
                                "    return false; " +
                                "  end;" +
                                "end",
                        Collections.<Object>singletonList(getName()), id.toString() + "-" + Thread.currentThread().getId());
        return opStatus;
    }

    // 获取当前线程只有指定锁的个数(也就是重入的次数)
    @Override
    public int getHoldCount() {
        Long opStatus = commandExecutor.evalRead(getName(), RedisCommands.EVAL_INTEGER,
                "local v = redis.call('get', KEYS[1]); " +
                                "if (v == false) then " +
                                "  return 0; " +
                                "else " +
                                "  local o = cjson.decode(v); " +
                                "  return o['c']; " +
                                "end",
                        Collections.<Object>singletonList(getName()));
        return opStatus.intValue();
    }

    @Override
    public boolean delete() {
        forceUnlock();
        return true;
    }

    @Override
    public Future<Boolean> deleteAsync() {
        return forceUnlockAsync();
    }

}
