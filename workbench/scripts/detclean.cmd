@echo off
rem 同一份地图、同一套设置，分别输出到两个目录，验证转换是否逐字节确定。
rem 注意必须写 cli，否则会启动网页界面。
setlocal
set JAVA=C:\Users\28315\AppData\Local\Programs\Java\temurin-21\bin\java.exe
set JAR=C:\Users\28315\Desktop\ai转换地图\dist\chunker-cli-1.20.0.jar
set SRC=D:\Vantaloom\data\temp\conv-20260912094016-8df320d6b1\scratch\conv-test\in_JAVA_1_15_2
set T=D:\Vantaloom\data\temp\detclean

if exist "%T%" rmdir /s /q "%T%"
mkdir "%T%"

echo [%TIME%] A start > "%T%\log.txt"
"%JAVA%" -Xmx3G -jar "%JAR%" cli -i "%SRC%" -f JAVA_1_12_2 -o "%T%\A" --shiftToFit > "%T%\a.log" 2>&1
echo [%TIME%] A exit=%ERRORLEVEL% >> "%T%\log.txt"

echo [%TIME%] B start >> "%T%\log.txt"
"%JAVA%" -Xmx3G -jar "%JAR%" cli -i "%SRC%" -f JAVA_1_12_2 -o "%T%\B" --shiftToFit > "%T%\b.log" 2>&1
echo [%TIME%] B exit=%ERRORLEVEL% >> "%T%\log.txt"

echo [%TIME%] DONE >> "%T%\log.txt"
