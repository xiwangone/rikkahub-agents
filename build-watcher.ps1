# 本脚本入公开仓库，故不写死本机绝对路径。
# 依赖环境变量：REPO_DIR（本仓库根目录）、ALIYUNPAN_DIR（云盘 CLI 目录）。
$repo = $env:REPO_DIR
$log = Join-Path $repo 'build-release.log'
$flag = Join-Path $repo 'build-release.done'
$upLog = Join-Path $repo 'build-release-upload.log'
$deadline = (Get-Date).AddMinutes(30)
while ((Get-Date) -lt $deadline) {
    if (Test-Path $log) {
        if (Select-String -Path $log -Pattern 'BUILD SUCCESSFUL|BUILD FAILED' -Quiet) { break }
    }
    Start-Sleep -Seconds 20
}
$ok = (Test-Path $log) -and (Select-String -Path $log -Pattern 'BUILD SUCCESSFUL' -Quiet)
if ($ok) {
    $apk = Get-ChildItem (Join-Path $repo 'app\build\intermediates\apk\release\*.apk') |
        Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if ($apk) {
        $dest = Join-Path $env:USERPROFILE ("rikkahub-" + (Get-Date -Format 'yyyyMMdd-HHmm') + ".apk")
        Copy-Item $apk.FullName $dest -Force
        Set-Location $env:ALIYUNPAN_DIR
        & .\aliyunpan.exe upload $dest '/AI中转站/软件包/' *> $upLog
        'UPLOAD_OK ' + (Get-Date -Format 'yyyy-MM-dd HH:mm:ss') | Out-File $flag -Encoding utf8
    } else {
        'APK_NOT_FOUND ' + (Get-Date -Format 'yyyy-MM-dd HH:mm:ss') | Out-File $flag -Encoding utf8
    }
} else {
    'BUILD_FAILED ' + (Get-Date -Format 'yyyy-MM-dd HH:mm:ss') | Out-File $flag -Encoding utf8
}
