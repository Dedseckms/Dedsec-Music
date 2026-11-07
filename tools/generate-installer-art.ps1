$ErrorActionPreference = 'Stop'
$projectPath = Split-Path $PSScriptRoot -Parent
Add-Type -AssemblyName System.Drawing
$canvas = [Drawing.Bitmap]::new(492, 942)
$g = [Drawing.Graphics]::FromImage($canvas)
try {
    $g.SmoothingMode = [Drawing.Drawing2D.SmoothingMode]::AntiAlias
    $g.TextRenderingHint = [Drawing.Text.TextRenderingHint]::AntiAliasGridFit
    $gradient = [Drawing.Drawing2D.LinearGradientBrush]::new([Drawing.Point]::new(0,0), [Drawing.Point]::new(492,942), [Drawing.Color]::FromArgb(23,47,83), [Drawing.Color]::FromArgb(34,29,68))
    $g.FillRectangle($gradient, 0,0,492,942); $gradient.Dispose()
    $blue = [Drawing.SolidBrush]::new([Drawing.Color]::FromArgb(168,200,255))
    $white = [Drawing.SolidBrush]::new([Drawing.Color]::FromArgb(235,240,250))
    $muted = [Drawing.SolidBrush]::new([Drawing.Color]::FromArgb(158,178,207))
    $ring = [Drawing.Pen]::new([Drawing.Color]::FromArgb(20,168,200,255), 2)
    for ($i=0; $i -lt 7; $i++) { $diameter = 160 + 62*$i; $g.DrawEllipse($ring, [single](280-$diameter/2), [single](535-$diameter/2), $diameter, $diameter) }
    $brandImage = [Drawing.Image]::FromFile((Join-Path $projectPath 'branding/app-icon.png'))
    try { $g.DrawImage($brandImage,48,64,164,164) } finally { $brandImage.Dispose() }
    $brandFont = [Drawing.Font]::new('Segoe UI',29,[Drawing.FontStyle]::Bold)
    $bodyFont = [Drawing.Font]::new('Segoe UI',20,[Drawing.FontStyle]::Regular)
    $smallFont = [Drawing.Font]::new('Segoe UI',12,[Drawing.FontStyle]::Regular)
    $g.DrawString("DEDSEC`nMUSIC",$brandFont,$white,55,255)
    $g.DrawString("YOUR LIBRARY.`nYOUR RHYTHM.",$bodyFont,$white,55,660)
    $g.DrawString('MATERIAL 3  /  WINDOWS',$smallFont,$muted,55,850)
    $canvas.Save((Join-Path $projectPath 'branding/installer-side.bmp'),[Drawing.Imaging.ImageFormat]::Bmp)
    $blue.Dispose(); $white.Dispose(); $muted.Dispose(); $ring.Dispose(); $brandFont.Dispose(); $bodyFont.Dispose(); $smallFont.Dispose()
} finally { $g.Dispose(); $canvas.Dispose() }
