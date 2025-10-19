@echo off 
setlocal 
pushd "%~dp0" 
mvnw.cmd %* 
popd 
endlocal