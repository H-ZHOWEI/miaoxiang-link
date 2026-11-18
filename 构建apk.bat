@echo off
chcp 65001 >nul
echo ============================================
echo   喵想联动 - 构建 APK
echo ============================================
echo.
set JAVA_HOME=C:/Users/Administrator/.jdks/jbr-21.0.11
call gradlew.bat assembleDebug --no-daemon
echo.
if exist "app\build\outputs\apk\debug\app-debug.apk" (
    copy /Y "app\build\outputs\apk\debug\app-debug.apk" "dist\MiaoXiangLink-v1.6-release.apk" >nul
    echo 打包成功：dist\MiaoXiangLink-v1.6-release.apk
) else (
    echo 打包失败，请查看上方错误。
)
pause
