@echo off
rem 三次转换验证热更新：全新 / 相同设置 / 改一条映射
rem 关键：这个 jar 不带 cli 参数会启动网页界面，必须显式写 cli。
setlocal
set JAVA=C:\Users\28315\AppData\Local\Programs\Java\temurin-21\bin\java.exe
set JAR=C:\Users\28315\Desktop\ai转换地图\dist\chunker-cli-1.20.0.jar
set SRC=D:\Vantaloom\data\temp\conv-20260912094016-8df320d6b1\scratch\conv-test\in_JAVA_1_15_2
set T=D:\Vantaloom\data\temp\hotverify
set LOG=%T%\log.txt

if exist "%T%" rmdir /s /q "%T%"
mkdir "%T%"

echo [%TIME%] PASS1 fresh > "%LOG%"
"%JAVA%" -Xmx3G -jar "%JAR%" cli -i "%SRC%" -f JAVA_1_12_2 -o "%T%\world" --shiftToFit > "%T%\p1.log" 2>&1
echo [%TIME%] PASS1 exit=%ERRORLEVEL% >> "%LOG%"

echo [%TIME%] PASS2 same settings again >> "%LOG%"
"%JAVA%" -Xmx3G -jar "%JAR%" cli -i "%SRC%" -f JAVA_1_12_2 -o "%T%\world" --shiftToFit > "%T%\p2.log" 2>&1
echo [%TIME%] PASS2 exit=%ERRORLEVEL% >> "%LOG%"

echo {"identifiers":[{"old_identifier":"minecraft:quartz_bricks","new_identifier":"minecraft:nether_brick"}]} > "%T%\one.json"
echo [%TIME%] PASS3 one mapping changed >> "%LOG%"
"%JAVA%" -Xmx3G -jar "%JAR%" cli -i "%SRC%" -f JAVA_1_12_2 -o "%T%\world" --shiftToFit -m "%T%\one.json" > "%T%\p3.log" 2>&1
echo [%TIME%] PASS3 exit=%ERRORLEVEL% >> "%LOG%"

echo [%TIME%] ALL DONE >> "%LOG%"
