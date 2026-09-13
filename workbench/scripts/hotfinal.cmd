@echo off
rem 热更新完整验证：三次转换写入同一目录，每次后统计时间戳分布。
rem 期望：PASS1 全部打 T1；PASS2 全部维持 T1（0 个变化）；PASS3 只有受影响的区块打 T3。
rem 注意必须写 cli，否则会启动网页界面。
setlocal
set JAVA=C:\Users\28315\AppData\Local\Programs\Java\temurin-21\bin\java.exe
set JAR=C:\Users\28315\Desktop\ai转换地图\dist\chunker-cli-1.20.0.jar
set PY=C:\Users\28315\AppData\Local\Programs\Python\Python312\python.exe
set SRC=D:\Vantaloom\data\temp\conv-20260912094016-8df320d6b1\scratch\conv-test\in_JAVA_1_15_2
set T=D:\Vantaloom\data\temp\hotfinal
set LOG=%T%\log.txt
set SCRIPTS=C:\Users\28315\Desktop\ai转换地图\workbench\scripts

if exist "%T%" rmdir /s /q "%T%"
mkdir "%T%"

echo === PASS1 fresh === >> "%LOG%"
"%JAVA%" -Xmx3G -jar "%JAR%" cli -i "%SRC%" -f JAVA_1_12_2 -o "%T%\world" --shiftToFit > "%T%\p1.log" 2>&1
echo PASS1 exit=%ERRORLEVEL% >> "%LOG%"
echo --- PASS1 timestamps --- >> "%LOG%"
"%PY%" "%SCRIPTS%\settimestamp.py" "%T%\world\region" report >> "%LOG%" 2>&1

echo === PASS2 identical settings === >> "%LOG%"
"%JAVA%" -Xmx3G -jar "%JAR%" cli -i "%SRC%" -f JAVA_1_12_2 -o "%T%\world" --shiftToFit > "%T%\p2.log" 2>&1
echo PASS2 exit=%ERRORLEVEL% >> "%LOG%"
echo --- PASS2 timestamps --- >> "%LOG%"
"%PY%" "%SCRIPTS%\settimestamp.py" "%T%\world\region" report >> "%LOG%" 2>&1

echo {"identifiers":[{"old_identifier":"minecraft:quartz_bricks","new_identifier":"minecraft:nether_brick"}]} > "%T%\one.json"
echo === PASS3 one mapping changed === >> "%LOG%"
"%JAVA%" -Xmx3G -jar "%JAR%" cli -i "%SRC%" -f JAVA_1_12_2 -o "%T%\world" --shiftToFit -m "%T%\one.json" > "%T%\p3.log" 2>&1
echo PASS3 exit=%ERRORLEVEL% >> "%LOG%"
echo --- PASS3 timestamps --- >> "%LOG%"
"%PY%" "%SCRIPTS%\settimestamp.py" "%T%\world\region" report >> "%LOG%" 2>&1

echo ALL DONE >> "%LOG%"
