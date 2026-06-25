$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat :core:ui:testDebugUnitTest --rerun-tasks
.\gradlew.bat :feature:ai-record:testDebugUnitTest --rerun-tasks
.\gradlew.bat :app:testDebugUnitTest --rerun-tasks
.\gradlew.bat :app:assembleDebug
.\gradlew.bat test
git diff --check
