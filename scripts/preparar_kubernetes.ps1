param(
    [string]$K3d = "$env:LOCALAPPDATA\APS-tools\k3d.exe",
    [switch]$SemBuild,
    [switch]$RecriarCluster
)

$ErrorActionPreference = 'Stop'
$raizAps = Split-Path $PSScriptRoot -Parent
Set-Location -LiteralPath $raizAps
$cluster = 'aps'
$contexto = 'k3d-aps'
$namespace = 'aps'
$portaIngress = '8088'

function Verificar {
    param([string]$Etapa)
    if ($LASTEXITCODE -ne 0) { throw "Falha em: $Etapa (código $LASTEXITCODE)" }
}

docker info --format '{{.ServerVersion}}' | Out-Null
Verificar 'Docker'

if (-not (Test-Path -LiteralPath $K3d)) {
    New-Item -ItemType Directory -Force -Path (Split-Path $K3d -Parent) | Out-Null
    Invoke-WebRequest 'https://github.com/k3d-io/k3d/releases/download/v5.9.0/k3d-windows-amd64.exe' -OutFile $K3d
}

if ($RecriarCluster) {
    & $K3d cluster delete $cluster 2>$null
}

$clustersJson = & $K3d cluster list -o json
Verificar 'listar clusters k3d'
$clusters = @($clustersJson | ConvertFrom-Json)
if ($clusters.name -notcontains $cluster) {
    & $K3d cluster create $cluster --servers 1 --agents 2 --port "${portaIngress}:80@loadbalancer" --wait
    Verificar 'criar cluster k3d/k3s'
}

kubectl config use-context $contexto | Out-Null
Verificar 'selecionar contexto k3d-aps'

# No Windows, o k3d pode gravar 0.0.0.0 no kubeconfig. Esse endereco e
# apropriado para escuta, mas pode travar o handshake TLS do kubectl.
# Mantemos a porta gerada pelo k3d e usamos o endereco local de conexao.
$servidorApi = kubectl config view --minify -o jsonpath='{.clusters[0].cluster.server}'
Verificar 'ler endereco do API Server'
$portaApi = ([Uri]$servidorApi).Port
kubectl config set-cluster $contexto --server="https://127.0.0.1:$portaApi" | Out-Null
Verificar 'normalizar endereco local do API Server'

foreach ($servico in @('manancial','alagamento','inversao-termica')) {
    if (-not $SemBuild) {
        docker build -t "aps-${servico}:3.0.0" "./servicos/$servico"
        Verificar "construir aps-${servico}:3.0.0"
    }
    & $K3d image import "aps-${servico}:3.0.0" -c $cluster
    Verificar "importar aps-${servico}:3.0.0"
}

if (-not $SemBuild) {
    docker build --build-arg APS_BUILD_VERSION=3.0.1 -t aps-manancial:3.0.1 ./servicos/manancial
    Verificar 'construir aps-manancial:3.0.1'
}
& $K3d image import aps-manancial:3.0.1 -c $cluster
Verificar 'importar aps-manancial:3.0.1'

if (-not (Test-Path -LiteralPath '.env')) {
    Copy-Item -LiteralPath '.env.example' -Destination '.env'
    Write-Warning 'Arquivo .env criado a partir do modelo. Troque as senhas antes de uso fora do laboratório.'
}

kubectl --context $contexto apply -f k8s/00-namespace.yaml
Verificar 'criar namespace'

$envEfetivo = Join-Path ([System.IO.Path]::GetTempPath()) "aps-k3d-$PID.env"
try {
    Copy-Item -LiteralPath '.env' -Destination $envEfetivo -Force
    $chaves = @{}
    Get-Content -LiteralPath $envEfetivo | ForEach-Object {
        if ($_ -match '^([A-Za-z_][A-Za-z0-9_]*)=') { $chaves[$Matches[1]] = $true }
    }
    $padroesInternos = [ordered]@{
        REDIS_PASSWORD = 'aps-redis-local'
        RABBITMQ_DEFAULT_USER = 'aps'
        RABBITMQ_DEFAULT_PASS = 'aps-rabbit-local'
    }
    foreach ($entrada in $padroesInternos.GetEnumerator()) {
        if (-not $chaves.ContainsKey($entrada.Key)) {
            Add-Content -LiteralPath $envEfetivo -Value "$($entrada.Key)=$($entrada.Value)"
        }
    }
    kubectl --context $contexto -n $namespace create secret generic aps-segredos --from-env-file=$envEfetivo --dry-run=client -o yaml |
        kubectl --context $contexto apply -f -
    Verificar 'criar Secret a partir do .env'
}
finally {
    if (Test-Path -LiteralPath $envEfetivo) { Remove-Item -LiteralPath $envEfetivo -Force }
}

kubectl --context $contexto apply -f k8s/
Verificar 'aplicar manifests'

foreach ($estado in @('redis','rabbitmq','manancial-estado','alagamento-estado','inversao-termica-estado')) {
    kubectl --context $contexto -n $namespace rollout status "statefulset/$estado" --timeout=300s
    Verificar "aguardar $estado"
}
foreach ($servico in @('manancial','alagamento','inversao-termica')) {
    kubectl --context $contexto -n $namespace rollout status "deployment/$servico" --timeout=300s
    Verificar "aguardar $servico"
}

Write-Host ''
Write-Host 'Cluster k3d/k3s pronto.' -ForegroundColor Green
Write-Host "Contexto: $contexto | Namespace: $namespace"
Write-Host "Ingress: http://127.0.0.1:${portaIngress}"
Write-Host 'Use: kubectl get all,ingress,pvc,hpa -n aps -o wide'
