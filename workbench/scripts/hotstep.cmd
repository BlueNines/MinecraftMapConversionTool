@echo off
rem 严格递进的热更新验证：每步只在原映射基础上「加」一条。
rem 第1、2 步验证「没改就不重写」；第 3 步验证「只重写受影响的那部分」。
rem 每步之间 sleep，让秒级时间戳能区分开。
rem 注意必须写 cli，否则会启动网页界面。
setlocal
set JAVA=C:\Users\28315\AppData\Local\Programs\Java\temurin-21\bin\java.exe
set JAR=C:\Users\28315\Desktop\ai转换地图\dist\chunker-cli-1.20.0.jar
set PY=C:\Users\28315\AppData\Local\Programs\Python\Python312\python.exe
set SRC=D:\Vantaloom\data\temp\conv-20260912094016-8df320d6b1\scratch\conv-test\in_JAVA_1_15_2
set T=D:\Vantaloom\data\temp\hotstep
set LOG=%T%\log.txt
set SCRIPTS=C:\Users\28315\Desktop\ai转换地图\workbench\scripts

if exist "%T%" rmdir /s /q "%T%"
mkdir "%T%"

rem 映射集 M1：把 dirt_path（893 个）换成 dirt
echo {"identifiers":[{"old_identifier":"minecraft:dirt_path","new_identifier":"minecraft:dirt"}]} > "%T%\m1.json"
rem 映射集 M2：M1 再加一条 cobblestone → stone
echo {"identifiers":[{"old_identifier":"minecraft:dirt_path","new_identifier":"minecraft:dirt"},{"old_identifier":"minecraft:cobblestone","new_identifier":"minecraft:stone"}]} > "%T%\m2.json"

echo === STEP1 fresh with M1 === >> "%LOG%"
"%JAVA%" -Xmx3G -jar "%JAR%" cli -i "%SRC%" -f JAVA_1_12_2 -o "%T%\world" --shiftToFit -m "%T%\m1.json" > "%T%\s1.log" 2>&1
echo STEP1 exit=%ERRORLEVEL% >> "%LOG%"
ping -n 3 127.0.0.1 > nul
echo --- STEP1 timestamps --- >> "%LOG%"
"%PY%" "%SCRIPTS%\settimestamp.py" "%T%\world\region" report >> "%LOG%" 2>&1

ping -n 3 127.0.0.1 > nul
echo === STEP2 same M1 again === >> "%LOG%"
"%JAVA%" -Xmx3G -jar "%JAR%" cli -i "%SRC%" -f JAVA_1_12_2 -o "%T%\world" --shiftToFit -m "%T%\m1.json" > "%T%\s2.log" 2>&1
echo STEP2 exit=%ERRORLEVEL% >> "%LOG%"
ping -n 3 127.0.0.1 > nul
echo --- STEP2 timestamps (期望：与 STEP1 完全一致，0 个重写) --- >> "%LOG%"
"%PY%" "%SCRIPTS%\settimestamp.py" "%T%\world\region" report >> "%LOG%" 2>&1

ping -n 3 127.0.0.1 > nul
echo === STEP3 M1 + cobblestone === >> "%LOG%"
"%JAVA%" -Xmx3G -jar "%JAR%" cli -i "%SRC%" -f JAVA_1_12_2 -o "%T%\world" --shiftToFit -m "%T%\m2.json" > "%T%\s3.log" 2>&1
echo STEP3 exit=%ERRORLEVEL% >> "%LOG%"
ping -n 3 127.0.0.1 > nul
echo --- STEP3 timestamps (期望：两组，只有含 cobblestone 的区块变新) --- >> "%LOG%"
"%PY%" "%SCRIPTS%\settimestamp.py" "%T%\world\region" report >> "%LOG%" 2>&1

echo ALL DONE >> "%LOG%"
