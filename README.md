# Video KYC Android SDK

A production-style Android Video KYC SDK designed to be embedded into a host application in the same way a payment/identity gateway SDK is integrated.

## Integration

### 1. Start KYC

```kotlin
private val kycLauncher = registerForActivityResult(KycSdkContract()) { result ->
    when (result?.resultCode) {
        "APPROVED" -> {
            // KYC approved
        }
        "REJECTED" -> {
            // KYC rejected
        }
        "CANCELLED" -> {
            // Customer cancelled
        }
        "ENDED" -> {
            // Agent/backend ended the call
        }
        "PERMISSION_DENIED" -> {
            // Camera/microphone permission was denied
        }
        "INVALID_REQUEST", "ERROR" -> {
            val message = result?.message.orEmpty()
            // Show the error to the host application
        }
    }
}

fun startVideoKyc() {
    kycLauncher.launch(
        KycSdkRequest(
            apiBaseUrl = "https://your-api.example.com",
            companyId = "YOUR_COMPANY_ID",
            customerId = "CUSTOMER_ID",
            customerName = "Customer Name",
            dateOfBirth = "1995-05-15",
            mobile = "9000000000",
            email = "customer@example.com",
            title = "Video KYC"
        )
    )
}
```

### 2. Optional microphone/camera controls

The SDK hides the microphone and camera controls by default. This gives the host application a clean payment-gateway-style verification experience.

To show them:

```kotlin
KycSdkRequest(
    apiBaseUrl = "https://your-api.example.com",
    companyId = "YOUR_COMPANY_ID",
    customerId = "CUSTOMER_ID",
    customerName = "Customer Name",
    showMediaControls = true
)
```

`showMediaControls = false` is the default.

### Result contract

`KycSdkResult` is returned to the host application with:

- `resultCode`
- `sessionId`
- `sessionCode`
- `status`
- `finalActionCode`
- `endedReason`
- `message`

The SDK does not manufacture an APPROVED result. The final result comes from the backend/WebSocket KYC lifecycle.

## UI

The SDK call screen is intentionally self-contained and professional:

- secure verification header
- session identifier
- verification status card
- real WebRTC video area
- optional microphone/camera controls
- explicit end-verification action
- cancel state
- error state
- security message
- `Developed by misba` branding at the bottom

## Gradle configuration

The existing Android/Gradle configuration has intentionally not been changed for this UI/API update.
