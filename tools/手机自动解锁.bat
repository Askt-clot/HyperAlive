@echo off
chcp 65001 >nul
title 手机自动解锁守护（重启后免手输密码）
cd /d "%~dp0"
echo 守护中：手机重启后（或锁屏时）会自动用 pin.txt 里的密码解锁。
echo 关掉这个窗口就停止守护。
echo.
python autounlock.py --always
pause