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
package org.redisson.client.codec;

import java.io.IOException;

import org.redisson.client.handler.State;
import org.redisson.client.protocol.Decoder;
import org.redisson.client.protocol.Encoder;

import io.netty.buffer.ByteBuf;
import io.netty.util.CharsetUtil;

public class StringCodec implements Codec {

    public static final StringCodec INSTANCE = new StringCodec();

    @Override
    public Decoder<Object> getValueDecoder() {
        return new Decoder<Object>() {
            @Override
            public Object decode(ByteBuf buf, State state) {
                return buf.toString(CharsetUtil.UTF_8);
            }
        };
    }

    @Override
    public Encoder getValueEncoder() {
        return new Encoder() {
            @Override
            public byte[] encode(Object in) throws IOException {
                return in.toString().getBytes("UTF-8");
            }
        };
    }

    @Override
    public Decoder<Object> getMapValueDecoder() {
        return getValueDecoder();
    }

    @Override
    public Encoder getMapValueEncoder() {
        return getValueEncoder();
    }

    @Override
    public Decoder<Object> getMapKeyDecoder() {
        return getValueDecoder();
    }

    @Override
    public Encoder getMapKeyEncoder() {
        return getValueEncoder();
    }

}
