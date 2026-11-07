# Run with Windows PowerShell -STA. Renders the actual VectorDrawable paths, cropped like an adaptive icon.
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName PresentationCore,PresentationFramework,WindowsBase
$project = Split-Path $PSScriptRoot -Parent
[xml]$vector = [IO.File]::ReadAllText((Join-Path $project 'app/src/main/res/drawable/ic_launcher_foreground.xml'))
$namespace = 'http://schemas.android.com/apk/res/android'
$visual = New-Object Windows.Media.DrawingVisual
$drawing = $visual.RenderOpen()
$bounds = New-Object Windows.Rect(0,0,512,512)
$clip = New-Object Windows.Media.RectangleGeometry($bounds,112,112)
$drawing.PushClip($clip)
[xml]$colors = [IO.File]::ReadAllText((Join-Path $project 'app/src/main/res/values/colors.xml'))
$backgroundColor = ($colors.resources.color | Where-Object { $_.name -eq 'launcher_background' }).InnerText
$background = [Windows.Media.BrushConverter]::new().ConvertFromString($backgroundColor)
$drawing.DrawRectangle($background,$null,$bounds)
$scale = 512.0 / 72.0
$matrix = New-Object Windows.Media.Matrix($scale,0,0,$scale,(-18*$scale),(-18*$scale))
$drawing.PushTransform([Windows.Media.MatrixTransform]::new($matrix))
foreach($path in $vector.vector.path) {
    $geometry = [Windows.Media.Geometry]::Parse($path.GetAttribute('pathData',$namespace))
    $fill = [Windows.Media.BrushConverter]::new().ConvertFromString($path.GetAttribute('fillColor',$namespace))
    $pen = $null
    if($path.HasAttribute('strokeColor',$namespace)) {
        $stroke = [Windows.Media.BrushConverter]::new().ConvertFromString($path.GetAttribute('strokeColor',$namespace))
        $pen = [Windows.Media.Pen]::new($stroke,[double]$path.GetAttribute('strokeWidth',$namespace))
        $pen.StartLineCap = [Windows.Media.PenLineCap]::Round
        $pen.EndLineCap = [Windows.Media.PenLineCap]::Round
        $pen.LineJoin = [Windows.Media.PenLineJoin]::Round
    }
    $drawing.DrawGeometry($fill,$pen,$geometry)
}
$drawing.Pop()
$drawing.Pop()
$drawing.Close()
$bitmap = [Windows.Media.Imaging.RenderTargetBitmap]::new(512,512,96,96,[Windows.Media.PixelFormats]::Pbgra32)
$bitmap.Render($visual)
$encoder = New-Object Windows.Media.Imaging.PngBitmapEncoder
$encoder.Frames.Add([Windows.Media.Imaging.BitmapFrame]::Create($bitmap))
$directory = Join-Path $project 'artifacts'
New-Item -ItemType Directory -Path $directory -Force | Out-Null
$output = Join-Path $directory 'icon-preview.png'
$stream = [IO.File]::Create($output)
try { $encoder.Save($stream) } finally { $stream.Dispose() }
Write-Output $output
