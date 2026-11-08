#!/system/bin/sh
# 可靠地（重新）拉起 LSPosed 守护进程。
#
# 两条铁律：
#   1) 重启 zygote 后 lspd 不会自动重生 → 必须显式拉起；
#   2) 改过 modules_config.db（装/卸模块、改作用域、apk_path 变化）后 **必须重启 lspd**，
#      否则它继续用内存里那份旧模块表 → 新进程（含 SystemUI）不会加载模块，
#      症状是只有 system_server 有 hook 日志、SystemUI 一条都没有。
#
# 传 --restart / -r 时先杀掉旧 lspd 再拉起。
LOG=/data/local/tmp/lspd_start.log
: > "$LOG"
FORCE=0
case "$1" in
    -r|--restart|--force) FORCE=1 ;;
esac

if [ "$FORCE" = "1" ] && ps -A | grep -q " lspd$"; then
    echo "kill old lspd: $(ps -A | grep ' lspd$')" | tee -a "$LOG"
    pkill -f " lspd$" 2>/dev/null || killall lspd 2>/dev/null
    i=0
    while [ $i -lt 10 ]; do
        ps -A | grep -q " lspd$" || break
        sleep 1
        i=$((i + 1))
    done
fi

if ps -A | grep -q " lspd$"; then
    echo "lspd already running: $(ps -A | grep ' lspd$')" | tee -a "$LOG"
    exit 0
fi

cd /data/adb/modules/zygisk_lsposed || exit 1
nohup ./daemon --force >>"$LOG" 2>&1 &

i=0
while [ $i -lt 20 ]; do
    sleep 1
    i=$((i + 1))
    if ps -A | grep -q " lspd$"; then
        echo "lspd up after ${i}s: $(ps -A | grep ' lspd$')" | tee -a "$LOG"
        exit 0
    fi
done

echo "FAILED to start lspd after ${i}s" | tee -a "$LOG"
ls -l /data/adb/lspd/log/ >> "$LOG" 2>&1
exit 1