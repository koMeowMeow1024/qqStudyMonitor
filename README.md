# qqStudyMonitor

班级 QQ 群里的学习委员。群里帮老师把通知收成作业并跟到截止，私聊里帮同学看自己的进度、讲学习上的问题。

同一个机器人，两条通道，两套权限。群里是全班共用的班务，私聊只对发消息的那一个人负责。

## 群里

只处理班务，由老师或指定的学习委员账号发布和关闭。

- 老师发送文字或通知截图。机器人整理出要做什么、截止时间，先回一张确认卡，确认后才写入。
- 同学 @ 它说「我交了」，只修改自己的状态。
- 有人问「还没交的有谁」，按群友名册点名，只显示交没交。
- 到截止时间，由定时任务主动在群里提醒。

看不清的时间、地点或事项会追问。不编造截止日期，不替老师判断成绩。

## 私聊

私聊同时做两件事。

- 班务查询：回答「我还没交什么」。只查这个人的作业状态。
- 学习答疑：回答知识点、习题和题目截图。先指出卡在哪一步，把这一步讲清楚，再留一个同类的小问题。

私聊里的讲题过程和个人计划不进入群上下文，也不进入别人的私聊。

## 技术栈

- Java 21 / Spring Boot 3.5
- LangChain4j + 通义千问（班务识图、私聊讲题）
- NapCat OneBot v11 WebSocket
- SQLite（作业、进度、课程资料、私聊记录）
- Docker / `docker compose`

## 配置

密钥与连接信息用环境变量，不要提交 `.env`。

| 变量 | 说明 | 默认值 |
| --- | --- | --- |
| `DASHSCOPE_API_KEY` | 百炼 API Key（**必填**） | 无 |
| `DASHSCOPE_MODEL` | 模型 | `qwen3.7-flash` |
| `DASHSCOPE_REPLY_TIMEOUT_SECONDS` | 单次调用超时（秒） | `90` |
| `ONEBOT_WS_URL` | NapCat WebSocket | `ws://127.0.0.1:3001/` |
| `ONEBOT_ACCESS_TOKEN` | NapCat token | 空 |
| `BOT_IMAGE_MAX_BYTES_PER_IMAGE` | 单图上限 | 5 MiB |
| `BOT_IMAGE_MAX_TOTAL_BYTES` | 多图合计上限 | 8 MiB |
| `BOT_PRIVATE_CONTEXT_MAX_MESSAGES` | 私聊带入历史的条数 | `12` |
| `BOT_REMINDER_LEAD_MINUTES` | 截止前多少分钟开始催交 | `30` |

`src/main/resources/application.yml` 中还需填写（或用 Spring 配置覆盖）：

| 项 | 说明 |
| --- | --- |
| `bot.qq.self-id` | 机器人 QQ，用于过滤自身消息和识别 @ |
| `bot.study.monitored-group-ids` | 班级群号；为空则处理所有群（不建议生产环境留空） |
| `bot.study.teacher-ids` | 老师 QQ，可发通知生成确认卡 |
| `bot.study.committee-ids` | 学习委员 QQ，可「确认发布」 |

`monitored-group-ids` 为空时，`isMonitoredGroup` 会放行所有群，仅适合本地调试。

## NapCat

机器人为 **OneBot v11 客户端**，连接 NapCat **正向 WebSocket 服务端**。

1. WebUI → 网络配置 → WebSocket 服务端：`host` 建议 `0.0.0.0`（容器访问用），`port` `3001`，`messagePostFormat` 建议 **`array`**。
2. 若设置 `token`，须与 `ONEBOT_ACCESS_TOKEN` 一致；鉴权头为 `Authorization: Bearer <token>`。
3. 连接成功日志：`已连接 NapCat：ws://...`

常用动作：`message` 事件（群/私聊）、`send_group_msg`、`send_private_msg`、`get_msg`、`get_image`。

## 运行

```bash
# PowerShell
$env:DASHSCOPE_API_KEY="sk-xxx"
mvn spring-boot:run
```

数据库文件默认 `qq-study-monitor.db`（项目根目录）；Docker 下为挂载卷 `data/qq-study-monitor.db`。

部署见 [DEPLOY.md](DEPLOY.md)。

## 数据库表（概要）

| 表 | 用途 |
| --- | --- |
| `assignment` | 已发布的作业与截止、是否已提醒 |
| `assignment_draft` | 待老师/委员确认的草稿 |
| `assignment_member_status` | 每位同学每项作业的 pending/submitted |
| `group_member` | 群友名册（催交与点名） |
| `course_material` | 由群通知整理的资料，供私聊讲题引用 |
| `private_message` | 私聊问答历史（按 user_id 隔离） |

建表脚本：`src/main/resources/schema.sql`。

## 范围之外

- 不连接教务系统，不自动批改，不代交作业。
- 不在群里闲聊；私聊不做通用生活管家。
