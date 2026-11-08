#!/system/bin/sh
# 高频截屏序列：抓「锁屏→熄屏」全过程
OUT=/sdcard/sq
mkdir -p $OUT
rm -f $OUT/*.png $OUT/times.txt $OUT/DONE
: > $OUT/times.txt
sleep 1
i=1
while [ $i -le 40 ]; do
  n=$(printf "%02d" $i)
  screencap -p $OUT/f$n.png
  echo "$n $(date +%s%3N)" >> $OUT/times.txt
  i=$((i+1))
done
echo done > $OUT/DONE