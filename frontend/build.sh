#!/bin/sh
# 前端构建并同步到 columbo-app 静态资源（访问路径 /app/）
set -e
cd "$(dirname "$0")"
npm run build
rm -rf ../columbo-app/src/main/resources/static/app
cp -R dist ../columbo-app/src/main/resources/static/app
echo "已同步到 columbo-app/src/main/resources/static/app（服务内路径 /app/）"
