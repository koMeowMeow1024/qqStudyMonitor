# 部署说明

用法与 [qqChatBot](https://github.com/koMeowMeow1024/qqChatBot) 相同：本地可用 NapCat on Windows + 容器内机器人；服务器可用 `docker compose --profile server`。

## 上传文件

不要上传 `target/`、`data/*.db`、`.env`：

```
Dockerfile
docker-compose.yml
pom.xml
src/
.env.example
```

容器内数据库路径为 `SPRING_DATASOURCE_URL` 中的 `/app/data/qq-study-monitor.db`。

## 配置要点

在 `.env` 或环境变量中设置（与 `application.yml` 对应）：

- `BOT_SELF_ID`：机器人 QQ
- `BOT_MONITORED_GROUP_IDS`：班级群号（逗号分隔）
- `BOT_TEACHER_IDS` / `BOT_COMMITTEE_IDS`：可发布、确认与关闭班务的 QQ（逗号分隔）
- `DASHSCOPE_API_KEY`、`ONEBOT_WS_URL`、`ONEBOT_ACCESS_TOKEN`

本地验证：`ONEBOT_WS_URL=ws://host.docker.internal:3001/`，NapCat 监听 `0.0.0.0:3001`。

服务器常驻：`docker compose --profile server up -d`，`.env` 中 `ONEBOT_WS_URL=ws://napcat:3001/`。

更多细节见仓库根目录 [README.md](README.md) 的 **NapCat** 与 **Docker** 小节。
