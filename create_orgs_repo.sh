#!/bin/bash


USER_NAME="rollenholt"
ORGS_NAME="rollenholt-SourceReading"
PROJECT_NAME="redisson"
PROJECT_DESCRIPTION="redisson 源码阅读"

git init
git add .
git commit -m 'first commit'

curl -u $USER_NAME https://api.github.com/orgs/"$ORGS_NAME"/repos -d "{\"name\":\"$PROJECT_NAME\", \"description\": \"$PROJECT_DESCRIPTION\"}"

git remote add origin git@github.com:$ORGS_NAME/"$PROJECT_NAME".git
git push -u origin master
