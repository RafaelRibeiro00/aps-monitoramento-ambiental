param([string]$Kind = "$env:LOCALAPPDATA\APS-tools\kind.exe", [switch]$SemBuild)
$ErrorActionPreference = 'Stop'
$raizAps = Split-Path $PSScriptRoot -Parent
Set-Location -LiteralPath $raizAps
function Verificar { if ($LASTEXITCODE -ne 0) { throw "Comando falhou com código $LASTEXITCODE" } }
if (-not (Test-Path -LiteralPath $Kind)) {
    New-Item -ItemType Directory -Force -Path (Split-Path $Kind -Parent) | Out-Null
    Invoke-WebRequest 'https://kind.sigs.k8s.io/dl/v0.33.0/kind-windows-amd64' -OutFile $Kind
}
$clusters = & $Kind get clusters
Verificar
if ($clusters -notcontains 'aps-experimentos') {
    & $Kind create cluster --config k8s/kind.yaml --wait 180s
    Verificar
}
python k8s/gerar.py
Verificar
foreach ($servico in @('manancial','alagamento','inversao-termica')) {
    if (-not $SemBuild) {
        docker build -t "aps-${servico}:3.0.0" "./servicos/$servico"
        Verificar
    }
    & $Kind load docker-image "aps-${servico}:3.0.0" --name aps-experimentos
    Verificar
}
if (-not $SemBuild) {
    docker build --build-arg APS_BUILD_VERSION=3.0.1 -t aps-manancial:3.0.1 ./servicos/manancial
    Verificar
}
& $Kind load docker-image aps-manancial:3.0.1 --name aps-experimentos
Verificar
kubectl --context kind-aps-experimentos apply -f k8s/metrics-server.yaml
Verificar
# Exceção somente para os certificados internos do laboratório kind local.
$patchMetricas = Join-Path $env:TEMP 'aps-metrics-patch.json'
'[{"op":"add","path":"/spec/template/spec/containers/0/args/-","value":"--kubelet-insecure-tls"}]' | Set-Content -LiteralPath $patchMetricas
kubectl --context kind-aps-experimentos -n kube-system patch deployment metrics-server --type=json --patch-file $patchMetricas
Verificar
kubectl --context kind-aps-experimentos apply -f k8s/aplicacao.json
Verificar
foreach ($servico in @('manancial','alagamento','inversao-termica')) {
    kubectl --context kind-aps-experimentos -n aps-lab rollout status "statefulset/${servico}-estado" --timeout=180s
    Verificar
    kubectl --context kind-aps-experimentos -n aps-lab rollout status "deployment/$servico" --timeout=180s
    Verificar
}
Write-Host 'Laboratório pronto: http://127.0.0.1:8088/manancial/historico'
