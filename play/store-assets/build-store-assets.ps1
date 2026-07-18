[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'

Add-Type -AssemblyName System.Drawing

$root = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$outputRoot = $PSScriptRoot
$iconDir = Join-Path $outputRoot 'icon'
$featureDir = Join-Path $outputRoot 'feature'
$phoneDir = Join-Path $outputRoot 'screenshots\phone'
$tabletDir = Join-Path $outputRoot 'screenshots\tablet'

foreach ($directory in @($iconDir, $featureDir, $phoneDir, $tabletDir)) {
    New-Item -ItemType Directory -Force -Path $directory | Out-Null
}

function New-RoundedRectanglePath {
    param(
        [Parameter(Mandatory)] [single] $X,
        [Parameter(Mandatory)] [single] $Y,
        [Parameter(Mandatory)] [single] $Width,
        [Parameter(Mandatory)] [single] $Height,
        [Parameter(Mandatory)] [single] $Radius
    )

    $diameter = $Radius * 2
    $path = [System.Drawing.Drawing2D.GraphicsPath]::new()
    $path.AddArc($X, $Y, $diameter, $diameter, 180, 90)
    $path.AddArc($X + $Width - $diameter, $Y, $diameter, $diameter, 270, 90)
    $path.AddArc($X + $Width - $diameter, $Y + $Height - $diameter, $diameter, $diameter, 0, 90)
    $path.AddArc($X, $Y + $Height - $diameter, $diameter, $diameter, 90, 90)
    $path.CloseFigure()
    return $path
}

function Draw-KingMark {
    param(
        [Parameter(Mandatory)] [System.Drawing.Graphics] $Graphics,
        [Parameter(Mandatory)] [single] $X,
        [Parameter(Mandatory)] [single] $Y,
        [Parameter(Mandatory)] [single] $Size
    )

    $state = $Graphics.Save()
    try {
        $Graphics.TranslateTransform($X, $Y)
        $Graphics.ScaleTransform($Size / 108.0, $Size / 108.0)

        $cream = [System.Drawing.SolidBrush]::new([System.Drawing.ColorTranslator]::FromHtml('#F7E9B0'))
        $green = [System.Drawing.SolidBrush]::new([System.Drawing.ColorTranslator]::FromHtml('#174E43'))
        try {
            # Exact geometry from drawable/ic_launcher_foreground.xml.
            $body = [System.Drawing.Drawing2D.GraphicsPath]::new()
            try {
                $body.StartFigure()
                $body.AddLine(51, 22, 57, 22)
                $body.AddLine(57, 22, 57, 31)
                $body.AddLine(57, 31, 65, 31)
                $body.AddLine(65, 31, 65, 37)
                $body.AddLine(65, 37, 57, 37)
                $body.AddLine(57, 37, 57, 45)
                $body.AddBezier(57, 45, 66, 47, 71, 54, 69, 62)
                $body.AddBezier(69, 62, 68, 67, 64, 71, 61, 75)
                $body.AddLine(61, 75, 68, 75)
                $body.AddLine(68, 75, 73, 87)
                $body.AddLine(73, 87, 35, 87)
                $body.AddLine(35, 87, 40, 75)
                $body.AddLine(40, 75, 47, 75)
                $body.AddBezier(47, 75, 44, 71, 40, 67, 39, 62)
                $body.AddBezier(39, 62, 37, 54, 42, 47, 51, 45)
                $body.AddLine(51, 45, 51, 37)
                $body.AddLine(51, 37, 43, 37)
                $body.AddLine(43, 37, 43, 31)
                $body.AddLine(43, 31, 51, 31)
                $body.CloseFigure()
                $Graphics.FillPath($cream, $body)
            } finally {
                $body.Dispose()
            }

            $cutout = [System.Drawing.Drawing2D.GraphicsPath]::new()
            try {
                $cutout.StartFigure()
                $cutout.AddBezier(43, 62, 46, 66, 50, 68, 54, 68)
                $cutout.AddBezier(54, 68, 58, 68, 62, 66, 65, 62)
                $cutout.AddBezier(65, 62, 65, 69, 60, 74, 54, 74)
                $cutout.AddBezier(54, 74, 48, 74, 43, 69, 43, 62)
                $cutout.CloseFigure()
                $Graphics.FillPath($green, $cutout)
            } finally {
                $cutout.Dispose()
            }

            $Graphics.FillRectangle($cream, 31, 88, 46, 7)
        } finally {
            $cream.Dispose()
            $green.Dispose()
        }
    } finally {
        $Graphics.Restore($state)
    }
}

function Export-CroppedScreenshot {
    param(
        [Parameter(Mandatory)] [string] $Source,
        [Parameter(Mandatory)] [string] $Destination,
        [Parameter(Mandatory)] [int] $CropX,
        [Parameter(Mandatory)] [int] $CropY,
        [Parameter(Mandatory)] [int] $CropWidth,
        [Parameter(Mandatory)] [int] $CropHeight,
        [Parameter(Mandatory)] [int] $OutputWidth,
        [Parameter(Mandatory)] [int] $OutputHeight
    )

    $sourcePath = Join-Path $root $Source
    if (-not (Test-Path -LiteralPath $sourcePath)) {
        throw "Screenshot source is missing: $sourcePath"
    }

    $image = [System.Drawing.Image]::FromFile($sourcePath)
    try {
        if ($CropX -lt 0 -or $CropY -lt 0 -or
            ($CropX + $CropWidth) -gt $image.Width -or
            ($CropY + $CropHeight) -gt $image.Height) {
            throw "Crop for $Source falls outside its $($image.Width)x$($image.Height) bounds."
        }

        $cropRatio = $CropWidth / [double] $CropHeight
        $outputRatio = $OutputWidth / [double] $OutputHeight
        if ([Math]::Abs($cropRatio - $outputRatio) -gt 0.0001) {
            throw "Crop and output aspect ratios do not match for $Destination."
        }

        # Play screenshots use 24-bit RGB PNGs. This also removes any source alpha channel.
        $bitmap = [System.Drawing.Bitmap]::new(
            $OutputWidth,
            $OutputHeight,
            [System.Drawing.Imaging.PixelFormat]::Format24bppRgb
        )
        try {
            $graphics = [System.Drawing.Graphics]::FromImage($bitmap)
            try {
                $graphics.CompositingMode = [System.Drawing.Drawing2D.CompositingMode]::SourceCopy
                $graphics.CompositingQuality = [System.Drawing.Drawing2D.CompositingQuality]::HighQuality
                $graphics.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
                $graphics.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality
                $graphics.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::HighQuality
                $graphics.DrawImage(
                    $image,
                    [System.Drawing.Rectangle]::new(0, 0, $OutputWidth, $OutputHeight),
                    [System.Drawing.Rectangle]::new($CropX, $CropY, $CropWidth, $CropHeight),
                    [System.Drawing.GraphicsUnit]::Pixel
                )
            } finally {
                $graphics.Dispose()
            }
            $bitmap.Save($Destination, [System.Drawing.Imaging.ImageFormat]::Png)
        } finally {
            $bitmap.Dispose()
        }
    } finally {
        $image.Dispose()
    }
}

function Export-FittedScreenshot {
    param(
        [Parameter(Mandatory)] [string] $Source,
        [Parameter(Mandatory)] [string] $Destination,
        [Parameter(Mandatory)] [int] $OutputWidth,
        [Parameter(Mandatory)] [int] $OutputHeight
    )

    $sourcePath = Join-Path $root $Source
    if (-not (Test-Path -LiteralPath $sourcePath)) {
        throw "Screenshot source is missing: $sourcePath"
    }

    $image = [System.Drawing.Image]::FromFile($sourcePath)
    try {
        $scale = [Math]::Min($OutputWidth / [double] $image.Width, $OutputHeight / [double] $image.Height)
        $drawWidth = [int] [Math]::Round($image.Width * $scale)
        $drawHeight = [int] [Math]::Round($image.Height * $scale)
        $drawX = [int] [Math]::Floor(($OutputWidth - $drawWidth) / 2.0)

        # Top anchoring keeps the device status bar in its natural position. Any
        # unused height extends the screenshot's existing black navigation area.
        $drawY = 0
        $bitmap = [System.Drawing.Bitmap]::new(
            $OutputWidth,
            $OutputHeight,
            [System.Drawing.Imaging.PixelFormat]::Format24bppRgb
        )
        try {
            $graphics = [System.Drawing.Graphics]::FromImage($bitmap)
            try {
                $graphics.Clear([System.Drawing.Color]::Black)
                $graphics.CompositingMode = [System.Drawing.Drawing2D.CompositingMode]::SourceCopy
                $graphics.CompositingQuality = [System.Drawing.Drawing2D.CompositingQuality]::HighQuality
                $graphics.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
                $graphics.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality
                $graphics.DrawImage(
                    $image,
                    [System.Drawing.Rectangle]::new($drawX, $drawY, $drawWidth, $drawHeight),
                    0,
                    0,
                    $image.Width,
                    $image.Height,
                    [System.Drawing.GraphicsUnit]::Pixel
                )
            } finally {
                $graphics.Dispose()
            }
            $bitmap.Save($Destination, [System.Drawing.Imaging.ImageFormat]::Png)
        } finally {
            $bitmap.Dispose()
        }
    } finally {
        $image.Dispose()
    }
}

function Clear-StatusBarIdentifiers {
    param(
        [Parameter(Mandatory)] [string] $Path,
        [Parameter(Mandatory)] [int] $X,
        [Parameter(Mandatory)] [int] $Y,
        [Parameter(Mandatory)] [int] $Width,
        [Parameter(Mandatory)] [int] $Height,
        [Parameter(Mandatory)] [int] $SampleX,
        [Parameter(Mandatory)] [int] $SampleY
    )

    $image = [System.Drawing.Bitmap]::new($Path)
    try {
        $backgroundColor = $image.GetPixel($SampleX, $SampleY)
        $bitmap = [System.Drawing.Bitmap]::new(
            $image.Width,
            $image.Height,
            [System.Drawing.Imaging.PixelFormat]::Format24bppRgb
        )
        try {
            $graphics = [System.Drawing.Graphics]::FromImage($bitmap)
            try {
                $graphics.DrawImageUnscaled($image, 0, 0)
                $brush = [System.Drawing.SolidBrush]::new($backgroundColor)
                try {
                    $graphics.FillRectangle($brush, $X, $Y, $Width, $Height)
                } finally {
                    $brush.Dispose()
                }
            } finally {
                $graphics.Dispose()
            }

            $temporaryPath = "$Path.sanitized.png"
            $bitmap.Save($temporaryPath, [System.Drawing.Imaging.ImageFormat]::Png)
        } finally {
            $bitmap.Dispose()
        }
    } finally {
        $image.Dispose()
    }

    Move-Item -LiteralPath $temporaryPath -Destination $Path -Force
}

function Export-StoreIcon {
    param([Parameter(Mandatory)] [string] $Destination)

    $bitmap = [System.Drawing.Bitmap]::new(
        512,
        512,
        [System.Drawing.Imaging.PixelFormat]::Format32bppArgb
    )
    try {
        $graphics = [System.Drawing.Graphics]::FromImage($bitmap)
        try {
            $graphics.CompositingMode = [System.Drawing.Drawing2D.CompositingMode]::SourceCopy
            $graphics.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
            $graphics.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality
            $graphics.Clear([System.Drawing.ColorTranslator]::FromHtml('#174E43'))
            $graphics.CompositingMode = [System.Drawing.Drawing2D.CompositingMode]::SourceOver
            Draw-KingMark -Graphics $graphics -X 0 -Y 0 -Size 512
        } finally {
            $graphics.Dispose()
        }
        $bitmap.Save($Destination, [System.Drawing.Imaging.ImageFormat]::Png)
    } finally {
        $bitmap.Dispose()
    }
}

function Export-FeatureGraphic {
    param([Parameter(Mandatory)] [string] $Destination)

    $bitmap = [System.Drawing.Bitmap]::new(
        1024,
        500,
        [System.Drawing.Imaging.PixelFormat]::Format24bppRgb
    )
    try {
        $graphics = [System.Drawing.Graphics]::FromImage($bitmap)
        try {
            $graphics.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
            $graphics.TextRenderingHint = [System.Drawing.Text.TextRenderingHint]::ClearTypeGridFit

            $background = [System.Drawing.Drawing2D.LinearGradientBrush]::new(
                [System.Drawing.Rectangle]::new(0, 0, 1024, 500),
                [System.Drawing.ColorTranslator]::FromHtml('#0C1C18'),
                [System.Drawing.ColorTranslator]::FromHtml('#174E43'),
                0.0
            )
            try {
                $graphics.FillRectangle($background, 0, 0, 1024, 500)
            } finally {
                $background.Dispose()
            }

            $cardPath = New-RoundedRectanglePath -X 62 -Y 70 -Width 320 -Height 360 -Radius 64
            $cardBrush = [System.Drawing.SolidBrush]::new([System.Drawing.ColorTranslator]::FromHtml('#174E43'))
            $cardPen = [System.Drawing.Pen]::new([System.Drawing.ColorTranslator]::FromHtml('#57C9AE'), 3)
            try {
                $graphics.FillPath($cardBrush, $cardPath)
                $graphics.DrawPath($cardPen, $cardPath)
            } finally {
                $cardPath.Dispose()
                $cardBrush.Dispose()
                $cardPen.Dispose()
            }

            Draw-KingMark -Graphics $graphics -X 72 -Y 78 -Size 300

            $creamBrush = [System.Drawing.SolidBrush]::new([System.Drawing.ColorTranslator]::FromHtml('#FFF8E7'))
            $mutedBrush = [System.Drawing.SolidBrush]::new([System.Drawing.ColorTranslator]::FromHtml('#C8DDD7'))
            $mintBrush = [System.Drawing.SolidBrush]::new([System.Drawing.ColorTranslator]::FromHtml('#57C9AE'))
            $titleFont = [System.Drawing.Font]::new('Segoe UI', 56, [System.Drawing.FontStyle]::Bold, [System.Drawing.GraphicsUnit]::Pixel)
            $taglineFont = [System.Drawing.Font]::new('Segoe UI', 31, [System.Drawing.FontStyle]::Regular, [System.Drawing.GraphicsUnit]::Pixel)
            $detailFont = [System.Drawing.Font]::new('Segoe UI', 19, [System.Drawing.FontStyle]::Bold, [System.Drawing.GraphicsUnit]::Pixel)
            try {
                $graphics.DrawString('Drawless Chess', $titleFont, $creamBrush, 420, 145)
                $graphics.DrawString('Every game has a winner.', $taglineFont, $mutedBrush, 423, 235)
                $graphics.DrawString('OFFLINE  •  DECISIVE  •  NO ADS', $detailFont, $mintBrush, 425, 316)
            } finally {
                $creamBrush.Dispose()
                $mutedBrush.Dispose()
                $mintBrush.Dispose()
                $titleFont.Dispose()
                $taglineFont.Dispose()
                $detailFont.Dispose()
            }
        } finally {
            $graphics.Dispose()
        }
        $bitmap.Save($Destination, [System.Drawing.Imaging.ImageFormat]::Png)
    } finally {
        $bitmap.Dispose()
    }
}

$storeIcon = Join-Path $iconDir 'drawless-chess-store-icon-512.png'
$featureGraphic = Join-Path $featureDir 'drawless-chess-feature-graphic-1024x500.png'

Export-StoreIcon -Destination $storeIcon
Export-FeatureGraphic -Destination $featureGraphic

$phoneScreenshots = @(
    @{
        Source = 'play\store-assets\source-captures\current\phone-home-current.png'
        Destination = (Join-Path $phoneDir 'phone-01-home.png')
        Crop = @(0, 80, 1080, 1920)
        Output = @(1080, 1920)
    },
    @{
        Source = 'play\store-assets\source-captures\current\phone-themes-current.png'
        Destination = (Join-Path $phoneDir 'phone-02-themes.png')
        Crop = @(0, 120, 1080, 1920)
        Output = @(1080, 1920)
    },
    @{
        Source = 'play\store-assets\source-captures\current\phone-gameplay-current.png'
        Destination = (Join-Path $phoneDir 'phone-03-gameplay.png')
        Crop = @(0, 80, 1080, 1920)
        Output = @(1080, 1920)
    },
    @{
        Source = 'play\store-assets\source-captures\current\phone-victory-current.png'
        Destination = (Join-Path $phoneDir 'phone-04-victory.png')
        Crop = @(0, 80, 1080, 1920)
        Output = @(1080, 1920)
    },
    @{
        Source = 'play\store-assets\source-captures\current\phone-defeat-current.png'
        Destination = (Join-Path $phoneDir 'phone-05-defeat.png')
        Crop = @(0, 80, 1080, 1920)
        Output = @(1080, 1920)
    }
)

$tabletScreenshots = @(
    @{
        Source = 'play\store-assets\source-captures\current\tablet-home-current.png'
        Destination = (Join-Path $tabletDir 'tablet-01-home.png')
        Crop = @(0, 80, 1200, 1838)
        Output = @(1200, 1838)
    },
    @{
        Source = 'play\store-assets\source-captures\current\tablet-themes-current.png'
        Destination = (Join-Path $tabletDir 'tablet-02-themes.png')
        Crop = @(0, 80, 1200, 1838)
        Output = @(1200, 1838)
    },
    @{
        Source = 'play\store-assets\source-captures\current\tablet-gameplay-current.png'
        Destination = (Join-Path $tabletDir 'tablet-03-gameplay.png')
        Crop = @(0, 80, 1200, 1838)
        Output = @(1200, 1838)
    },
    @{
        Source = 'play\store-assets\source-captures\current\tablet-victory-current.png'
        Destination = (Join-Path $tabletDir 'tablet-04-victory.png')
        Crop = @(0, 80, 1200, 1838)
        Output = @(1200, 1838)
    },
    @{
        Source = 'play\store-assets\source-captures\current\tablet-defeat-landscape-current.png'
        Destination = (Join-Path $tabletDir 'tablet-05-defeat-landscape.png')
        Crop = @(0, 64, 2000, 1054)
        Output = @(2000, 1054)
    }
)

foreach ($item in $phoneScreenshots + $tabletScreenshots) {
    if ($item.Fit) {
        Export-FittedScreenshot `
            -Source $item.Source `
            -Destination $item.Destination `
            -OutputWidth $item.Output[0] `
            -OutputHeight $item.Output[1]
    } else {
        Export-CroppedScreenshot `
            -Source $item.Source `
            -Destination $item.Destination `
            -CropX $item.Crop[0] `
            -CropY $item.Crop[1] `
            -CropWidth $item.Crop[2] `
            -CropHeight $item.Crop[3] `
            -OutputWidth $item.Output[0] `
            -OutputHeight $item.Output[1]
    }
}

$assets = Get-ChildItem -LiteralPath $iconDir, $featureDir, $phoneDir, $tabletDir -File -Filter '*.png'
$results = foreach ($asset in $assets) {
    $image = [System.Drawing.Image]::FromFile($asset.FullName)
    try {
        [pscustomobject]@{
            File = $asset.FullName.Substring($outputRoot.Length + 1).Replace('\', '/')
            Width = $image.Width
            Height = $image.Height
            PixelFormat = $image.PixelFormat.ToString()
            Bytes = $asset.Length
            SHA256 = (Get-FileHash -LiteralPath $asset.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
        }
    } finally {
        $image.Dispose()
    }
}

$iconResult = $results | Where-Object File -eq 'icon/drawless-chess-store-icon-512.png'
if ($iconResult.Width -ne 512 -or $iconResult.Height -ne 512 -or $iconResult.Bytes -gt 1MB) {
    throw 'The store icon failed its 512x512 / 1 MB validation.'
}

$featureResult = $results | Where-Object File -eq 'feature/drawless-chess-feature-graphic-1024x500.png'
if ($featureResult.Width -ne 1024 -or $featureResult.Height -ne 500 -or $featureResult.PixelFormat -ne 'Format24bppRgb') {
    throw 'The feature graphic failed its 1024x500 / 24-bit RGB validation.'
}

$screenshotResults = $results | Where-Object File -like 'screenshots/*'
foreach ($result in $screenshotResults) {
    if ($result.PixelFormat -ne 'Format24bppRgb') {
        throw "$($result.File) is not a 24-bit RGB PNG."
    }
    if ($result.Width -lt 1080 -and $result.Height -lt 1080) {
        throw "$($result.File) does not reach 1080 pixels on either dimension."
    }
    $longSide = [Math]::Max($result.Width, $result.Height)
    $shortSide = [Math]::Min($result.Width, $result.Height)
    if ($longSide -gt ($shortSide * 2)) {
        throw "$($result.File) exceeds Play's 2:1 maximum dimension ratio."
    }
}

$results | Sort-Object File | Format-Table -AutoSize
