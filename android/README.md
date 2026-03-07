# Mindaplus Android App v0.2

Android application for monitoring 3 transfers (T100/T200/T300) using camera-based template matching and automatic lane detection with Telegram notifications.

## Features (v0.2)

- **Template-based monitoring**: Advanced template matching for state detection (NEW)
- **Automatic lane detection**: Computer vision identifies transfer regions automatically (NEW)
- **Training workflow**: Complete training system with visual feedback (NEW)
- **Camera-based monitoring**: Uses CameraX for real-time video analysis
- **Telegram notifications**: Sends messages on state transitions only (anti-spam)
- **Landscape orientation**: Optimized for tripod mounting
- **5-second analysis interval**: Fixed analysis frequency as specified

## Requirements

- Android 5.0+ (API 21+)
- Camera permissions
- Internet permissions (for Telegram)
- Telegram Bot Token and Chat ID

## Setup Instructions

### 1. Open in Android Studio

1. Open Android Studio
2. Select "Open an existing Android Studio project"
3. Navigate to the `android/` folder and click "OK"
4. Wait for Gradle sync to complete

### 2. Configure Telegram Bot

1. Create a Telegram bot by messaging [@BotFather](https://t.me/botfather)
2. Get your bot token (format: `123456789:ABCdefGHIjklMNOpqrsTUVwxyz`)
3. Get your chat ID:
   - Add your bot to a group or start a conversation
   - Send a message to the bot
   - Visit: `https://api.telegram.org/bot<YOUR_BOT_TOKEN>/getUpdates`
   - Find your chat ID in the response

### 3. Build and Run

1. Connect your Android device or start an emulator
2. Click "Run" in Android Studio
3. Grant camera permission when prompted
4. Enter your Telegram Bot Token and Chat ID
5. Click "Start Monitoring"

## Training Workflow (NEW in v0.2)

Mindaplus v0.2 includes a complete training system that allows you to teach the app to recognize different states (OK, OBSTÁCULO, FALLO) for each transfer. See [TRAINING_WORKFLOW.md](../TRAINING_WORKFLOW.md) for detailed instructions.

### Quick Training Steps

1. **Open Training**: Tap "Entrenamiento" from the main screen
2. **Select Transfer**: Choose T100, T200, or T300 (upper/middle/lower lanes)
3. **Select State**: Choose the current state (OK, OBSTÁCULO, FALLO)
4. **Position Transfer**: Ensure the transfer shows the selected state
5. **Capture**: Press "Capturar plantilla" - system auto-detects lane region
6. **Verify**: Check the template preview shows correct region
7. **Save**: Press "Guardar" to confirm the capture
8. **Repeat**: Complete all 9 templates (3 transfers × 3 states)

### Training Features

- **Automatic lane detection**: System identifies transfer regions automatically
- **Visual feedback**: Template preview with cropped image and region coordinates
- **Progress tracking**: Shows 0/9 → 9/9 completion status
- **Error handling**: Clear messages for common issues
- **Smart validation**: Save button only enabled after valid capture

## Usage

### Camera Alignment

1. **Mount your phone on a tripod** in landscape orientation
2. **Position the camera** to capture all 3 transfer tracks
3. **Ensure good lighting** for optimal color detection
4. **Align the ROIs** (Regions of Interest) with the transfer tracks

### ROI Configuration

The app uses fixed ROIs defined in `TransferMonitor.kt`:

```kotlin
// Relative coordinates (0.0 - 1.0)
ROI_T100: left=0.1, top=0.2, right=0.3, bottom=0.4
ROI_T200: left=0.4, top=0.2, right=0.6, bottom=0.4  
ROI_T300: left=0.7, top=0.2, right=0.9, bottom=0.4
```

### Orange Detection Settings

Located in `TransferMonitor.kt`:

```kotlin
// HSV color ranges for orange detection
HUE_MIN = 10f, HUE_MAX = 25f
SAT_MIN = 0.5f, SAT_MAX = 1.0f
VAL_MIN = 0.4f, VAL_MAX = 1.0f
ORANGE_THRESHOLD = 0.15f // 15% of ROI must be orange
```

## Customization

### Adjusting ROIs

To modify ROI positions, edit the `RectF` values in `TransferMonitor.kt`:

```kotlin
private val ROI_T100 = RectF(left, top, right, bottom)
```

- `left`, `top`, `right`, `bottom` are relative coordinates (0.0 to 1.0)
- Example: `RectF(0.1f, 0.2f, 0.3f, 0.4f)` means:
  - Left: 10% from left edge
  - Top: 20% from top edge
  - Right: 30% from left edge
  - Bottom: 40% from top edge

### Adjusting Orange Detection

To modify orange detection sensitivity, edit the thresholds in `TransferMonitor.kt`:

```kotlin
// Orange detection thresholds
private const val HUE_MIN = 10f  // Orange hue range (adjust for different orange shades)
private const val HUE_MAX = 25f
private const val ORANGE_THRESHOLD = 0.15f  // Lower = more sensitive, Higher = less sensitive
```

## Troubleshooting

### Camera Issues

- **Permission denied**: Check app permissions in Settings > Apps > Mindaplus > Permissions
- **Black screen**: Ensure camera is not being used by another app
- **Poor detection**: Check lighting conditions and camera focus

### Telegram Issues

- **Messages not sending**: Verify bot token and chat ID are correct
- **No notifications**: Check internet connection and Telegram bot permissions
- **Rate limiting**: Bot messages are limited to 30 messages per second

### Detection Issues

- **False positives**: Increase `ORANGE_THRESHOLD` value
- **Missed detection**: Decrease `ORANGE_THRESHOLD` value
- **Wrong color detection**: Adjust HSV ranges for your specific orange shade

## Logs

Monitor app behavior using Android Studio's Logcat with tag "Mindaplus":

```
adb logcat -s Mindaplus
```

## Development Notes

- Uses Jetpack Compose for UI
- CameraX for camera operations
- OkHttp for Telegram API requests
- Coroutines for async operations
- Fixed 5-second analysis interval (v0.1 requirement)

## Next Steps (v0.2+)

- UI sliders for ROI adjustment
- UI controls for orange threshold tuning
- Debug mode with frame saving
- Performance optimizations

## Support

For issues or questions, check:
1. Camera alignment and lighting
2. Telegram bot configuration
3. ROI positioning relative to transfer tracks
4. Orange color detection thresholds