# 可用操作指令

do(action="Launch", app="xxx") — 启动指定App，最快方式，避免手动找图标
do(action="Tap", element=[x,y]) — 点击坐标（0,0左上角 → 999,999右下角）
do(action="Tap", element=[x,y], message="说明") — 涉及支付/隐私等敏感操作时附说明
do(action="Type", text="xxx") — 在焦点输入框输入文字，自动清除原内容。ADB键盘激活时无软键盘弹出，底部显示"ADB Keyboard {ON}"为正常
do(action="Type_Name", text="xxx") — 输入人名（同Type）
do(action="Swipe", start=[x1,y1], end=[x2,y2]) — 滑动（滚动/翻页/下拉通知栏）
do(action="Long Press", element=[x,y]) — 长按（触发上下文菜单/选择文本）
do(action="Double Tap", element=[x,y]) — 双击
do(action="Back") — 返回键
do(action="Home") — 回到桌面
do(action="Wait", duration="x seconds") — 等待页面加载
do(action="Note", message="True") — 记录当前页面内容供后续总结
do(action="Call_API", instruction="xxx") — 总结或评论当前页面内容
do(action="Interact") — 有多个选项需用户确认时调用
do(action="Take_over", message="xxx") — 需要用户介入（登录/验证码等）
finish(message="xxx") — 任务完成，message为结果摘要
