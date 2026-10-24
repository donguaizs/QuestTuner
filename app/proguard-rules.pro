# QuestTuner R8 规则
# 工程未使用反射；manifest 引用组件（MainActivity/ForegroundProfileService/
# BootReceiver/InstallResultReceiver）由 AGP 自动 keep；
# coroutines / compose 规则内置于依赖自身。当前无需额外 keep。
