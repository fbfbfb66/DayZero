$path = "app/src/main/java/com/example/ui/screens/AiRecordScreen.kt"
$content = Get-Content $path -Raw

$content = $content -replace "\s*\.imeNestedScroll\(\)", ""

$oldLogic = "var isFocused by remember \{ mutableStateOf\(false\) \}\r?\n\s*val imeTargetBottom = WindowInsets\.imeAnimationTarget\.getBottom\(LocalDensity\.current\)\r?\n\s*val isSeparated = isFocused \|\| \(imeTargetBottom > 0\)\r?\n\r?\n\s*val focusManager = LocalFocusManager\.current\r?\n\s*LaunchedEffect\(imeTargetBottom\) \{\r?\n\s*if \(imeTargetBottom == 0 && isFocused\) \{\r?\n\s*focusManager\.clearFocus\(\)\r?\n\s*\}\r?\n\s*\}"

$newLogic = @"
var isFocused by remember { mutableStateOf(false) }
            val imeTargetBottom = WindowInsets.imeAnimationTarget.getBottom(LocalDensity.current)
            val isSeparated = isFocused || (imeTargetBottom > 0) || inputText.isNotEmpty()

            val focusManager = LocalFocusManager.current
            LaunchedEffect(imeTargetBottom) {
                if (imeTargetBottom == 0 && isFocused) {
                    focusManager.clearFocus()
                }
            }
            LaunchedEffect(listState.isScrollInProgress) {
                if (listState.isScrollInProgress) {
                    focusManager.clearFocus()
                }
            }
"@

$content = $content -replace $oldLogic, $newLogic

[IO.File]::WriteAllText((Resolve-Path $path).Path, $content)
Write-Host "Done"
