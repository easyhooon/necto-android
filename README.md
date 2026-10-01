# necto-android

[Necto](https://github.com/toss/necto)(iOS 디버깅 플랫폼)의 **앱 내장 SDK를 Android로 포팅**한 라이브러리.
Mac의 Necto 앱과 웹 패널은 그대로 쓰고, Android 앱이 iOS 앱과 같은 프로토콜(v1)로 붙는다.

## 연결 방식

SDK가 앱 안에서 `127.0.0.1:9979`(점유 시 9986까지)를 listen하고, Mac의 Necto는 시뮬레이터용으로 loopback 포트를 계속 probe한다.
따라서 **Mac 앱 수정 없이** adb 포워딩만 하면 된다.

```bash
adb forward tcp:9979 tcp:9979
```

핸드셰이크의 `simulatorID`에 `android:<ANDROID_ID>`를 넣어 기기별로 구분된다. 두 기기를 동시에 붙이려면 두 번째 기기는 다른 로컬 포트(예: `adb -s <serial> forward tcp:9980 tcp:9979`)로 포워딩.

## 모듈

| 모듈 | 내용 | iOS 대응 |
| --- | --- | --- |
| `necto-core` (순수 JVM) | 프로토콜 모델·JSON·스키마 검증, 길이 프리픽스 소켓 전송, SDK 런타임(코루틴), Events·Network·Performance·Files·UI Control 플러그인 공통부, 웹 패널 | `NectoModel`, `NectoTransport`, `NectoSDK`, `NectoDefaultPlugins` |
| `necto-android` | Context 기반 앱 identity, DataStore Preferences 플러그인, 프로세스 성능 샘플러(CPU·PSS·FPS·스레드), View 기반 UI Control, 기본 Files 루트 | `NectoProcessMetrics`, UIKit 부분 |
| `necto-okhttp` | OkHttp `Interceptor`로 네트워크 캡처 | `NectoURLSessionCapture` |

## 사용법

```kotlin
val Context.settings by preferencesDataStore("settings")

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) {
            val events = NectoEventsPlugin()
            val network = NectoNetworkPlugin()
            NectoAndroid.start(
                this,
                NectoAndroidPlugins.defaults(this, events, network, dataStores = mapOf("settings" to settings)),
            )

            okHttpClient = OkHttpClient.Builder()
                .addInterceptor(NectoOkHttpInterceptor(network))
                .build()
            events.report(NectoEvent(NectoEvent.Level.INFO, "App", "started"))
        }
    }
}
```

커스텀 플러그인은 iOS와 동일한 형태:

```kotlin
class ThingsPlugin : NectoPlugin {
    override val id = "com.example.things"
    override fun register(necto: NectoRegistrar) {
        necto.handle("things.list") { jsonObject("things" to jsonArray(...)) }
        necto.stream("things.observe") { _, out -> changes.collect { out.send(it) } }
    }
}
```

## iOS와 다른 점

- Preferences: `UserDefaults` 대신 Jetpack DataStore Preferences. DataStore는 파일당 인스턴스 하나만 허용되므로 앱이 가진 인스턴스를 이름과 함께 넘김 (`dataStores = mapOf("settings" to context.settings)`). 첫 번째가 기본 store. Long/Float는 패널에서 Int/Double로 편집되고 저장 시 원래 타입 유지. ByteArray 값은 읽기 전용.
- Files 루트: `files`, `cache`, `data`(shared_prefs·databases 포함), `external`, `external-cache`.
- Performance: `memory` = total PSS, `resident-memory` = VmRSS, `compressed-memory` = VmSwap(zram), 추가로 `java-heap`, `native-heap`.
- 기기 표시: Necto Mac 앱은 `osVersion`을 항상 "iOS ..."로 표시하므로 `osVersion`은 비워 두고, Android 버전은 기기 이름 뒤에 붙인다 (예: `지훈의 S25+ · Android 17`).
- UI Control: View 트리와 Jetpack Compose semantics 트리를 함께 읽는다(Compose는 앱에 있을 때만, `compileOnly` 의존). 좌표는 px. back은 시스템 BACK 키(`method: "backKey"`). 터치를 받지 않는 오버레이(edge-to-edge의 `ProtectionLayout` 등)는 가림으로 보지 않는다. 다이얼로그/팝업 창은 아직 대상이 아님.
- 패널은 Java 리소스(`necto/panels/<id>` + 빌드 시 생성되는 `files.txt` 인덱스)로 패키징.

## 샘플 앱

`sample`은 Necto의 iOS `ExampleApp`을 화면 단위로 옮긴 앱 (Jetpack Compose, 하단 탭 5개).

| 탭 | 내용 | iOS 대응 |
| --- | --- | --- |
| Connection | SDK 상태(포트·연결 앱)·프로토콜 버전, listen 중지/시작 | `ConnectionView` |
| Network | 앱 안의 loopback REST 서버(`LocalApi`)로 보내는 샘플 요청(GET·POST·PATCH·DELETE, 큰 응답, 느린 응답, 404, 500, DNS 실패) | `NetworkView`, `LocalAPI` |
| Control | UI Control fixture. Compose와 View(`AndroidView`) 두 벌을 칩으로 전환, 레이블·식별자(`poc.*`)는 iOS와 동일 | `ControlFixtureController` |
| Accessibility | Compose fixture(`ax.*`): 카운터, 비활성 버튼, 입력란, detail, sheet, 커스텀 스크롤 | `AccessibilityFixture` |
| About | SDK·프로토콜 버전 | `AboutView` |

앱 operation을 받는 커스텀 플러그인 `plugin-sample`(`com.example.app.state`, `com.example.app.counter`)은 iOS와 같은 웹 패널을 Java 리소스(`sample/src/main/resources/panels/plugin-sample`)로 함께 싣는다. 실행 시 DataStore Preferences와 `files/`에 예시 값·파일을 채운다.

## 빌드

```bash
./gradlew build                          # 전체 (Google Maven 필요)
./gradlew -Pnecto.skipAndroid=true test  # JVM 모듈만
```

minSdk 26, compileSdk 35, Kotlin 2.0.21, AGP 8.7.3.

## 라이선스

MIT. 원본 Necto © Viva Republica, Inc. — `LICENSE`, `NOTICE` 참고.
