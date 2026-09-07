$log = 'A:\workspace\repos\rikkahub-agents\build-release.log'
$flag = 'A:\workspace\repos\rikkahub-agents\build-release.done'
$upLog = 'A:\workspace\repos\rikkahub-agents\build-release-upload.log'
$deadline = (Get-Date).AddMinutes(30)
while ((Get-Date) -lt $deadline) {
    if (Test-Path $log) {
        if (Select-String -Path $log -Pattern 'BUILD SUCCESSFUL|BUILD FAILED' -Quiet) { break }
    }
    Start-Sleep -Seconds 20
}
$ok = (Test-Path $log) -and (Select-String -Path $log -Pattern 'BUILD SUCCESSFUL' -Quiet)
if ($ok) {
    $apk = Get-ChildItem 'A:\workspace\repos\rikkahub-agents\app\build\intermediates\apk\release\*.apk' | Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if ($apk) {
        $dest = 'C:\Users\chen\rikkahub-2.47.3.apk'
        Copy-Item $apk.FullName $dest -Force
        Set-Location 'C:\tools\aliyunpan\aliyunpan-v0.4.0-windows-x64'
        & .\aliyunpan.exe upload $dest '/AI中转站/软件包/' *> $upLog
        'UPLOAD_OK ' + (Get-Date -Format 'yyyy-MM-dd HH:mm:ss'') | Out-File $flag -Encoding utf8
    } else {
        'APK_NOT_FOUND ' + (Get-Date -Format 'yyyy-MM-dd HH:mm:ss'') | Out-File $flag -Encoding utf8
    }
} else {
    'BUILD_FAILED ' + (Get-Date -Format 'yyyy-MM-dd HH:mm:ss'') | Out-File $flag -Encoding utf8
}
