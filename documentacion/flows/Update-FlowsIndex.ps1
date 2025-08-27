# --- Config índice de flujos ---
$FLOWS_DIR   = "documentacion/flows"
$FLOWS_INDEX = Join-Path $FLOWS_DIR "flows.json"
$FLOWS_KEEP  = 200   # número máximo de flujos a mantener en el índice

function Update-FlowsIndex {
  param(
    [Parameter(Mandatory=$true)][string]$FlowId,
    [Parameter(Mandatory=$true)][string]$HtmlFileName,  # ej: "$FlowId.html"
    [Parameter(Mandatory=$true)][DateTime]$When
  )

  # Asegurar carpeta
  if (-not (Test-Path $FLOWS_DIR)) {
    New-Item -ItemType Directory -Path $FLOWS_DIR -Force | Out-Null
  }

  # Cargar JSON existente (con reintentos por si hay otro proceso escribiendo)
  $attempts = 0; $maxAttempts = 5; $waitMs = 150
  $list = @()
  while ($attempts -lt $maxAttempts) {
    try {
      if (Test-Path $FLOWS_INDEX) {
        $raw = Get-Content -LiteralPath $FLOWS_INDEX -Raw -ErrorAction Stop
        if ($raw.Trim().Length -gt 0) {
          $list = ConvertFrom-Json -InputObject $raw -ErrorAction Stop
        }
      }
      break
    } catch {
      Start-Sleep -Milliseconds $waitMs
      $attempts++
    }
  }
  if (-not $list) { $list = @() }

  # Normalizar a array de PSObjects
  if ($list -isnot [System.Collections.IEnumerable]) { $list = @($list) }

  # Quitar si ya existe (evitar duplicados por mismo ID)
  $list = @($list | Where-Object { $_.id -ne $FlowId })

  # Insertar nuevo al principio
  $entry = [pscustomobject]@{
    id   = $FlowId
    date = $When.ToString("yyyy-MM-dd HH:mm:ss")
    file = $HtmlFileName
  }
  $list = ,$entry + $list

  # Truncar a FLOWS_KEEP elementos
  if ($list.Count -gt $FLOWS_KEEP) {
    $list = $list | Select-Object -First $FLOWS_KEEP
  }

  # Guardar con reintentos
  $attempts = 0
  while ($attempts -lt $maxAttempts) {
    try {
      ($list | ConvertTo-Json -Depth 6 | Out-String).Trim() | Set-Content -LiteralPath $FLOWS_INDEX -Encoding UTF8 -NoNewline
      break
    } catch {
      Start-Sleep -Milliseconds $waitMs
      $attempts++
      if ($attempts -ge $maxAttempts) {
        # Evitar $var: dentro de comillas que PowerShell interpreta mal; usar formato para mayor seguridad
        Write-Warning ("No se pudo actualizar {0}: {1}" -f $FLOWS_INDEX, $_.Exception.Message)
      }
    }
  }
}
