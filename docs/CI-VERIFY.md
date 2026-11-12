# Verifica GitHub Actions

Questo branch serve a verificare il trigger automatico `pull_request`.
Il workflow deve eseguire Gradle 8.11.1 + AGP 8.10.1 su JDK 17 e SDK API 36.
L'artefatto APK debug deve essere presente nell'output di GitHub Actions.
Se la build non si avvia, verificare Settings > Actions > General e il permesso per eseguire i workflow.
Non viene modificato Cloudy-Scanner.

CI retry: uses the preinstalled Android SDK on ubuntu-latest, avoiding the unavailable legacy `sdkmanager tools` package.
