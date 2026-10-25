curl https://open.bigmodel.cn/api/paas/v4/chat/completions -H "Content-Type: application/json" -H "Authorization: Bearer sk-你的APIKEY -d '{
  "model": "glm-4.6",
  "messages": [
    {
      "role": "user",
      "content": "1+1"
    }
  ]
}'