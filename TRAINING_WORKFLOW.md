# Mindaplus Training Workflow Documentation

## Overview

The training workflow in Mindaplus v0.2 allows operators to capture template images for each transfer (T100, T200, T300) in different states (OK, OBSTACULO, FALLO). The system uses automatic lane detection to identify the correct region for each transfer and provides visual feedback during the training process.

## Training Screen Components

### 1. Training Progress
- **Location**: Top of the right panel
- **Purpose**: Shows current training progress (e.g., "3/9 templates")
- **Visual**: Progress bar that fills as templates are captured
- **Behavior**: Updates automatically after each successful capture

### 2. Transfer Selection
- **Purpose**: Select which transfer lane to train
- **Options**: T100 (upper lane), T200 (middle lane), T300 (lower lane)
- **Behavior**: 
  - Only one transfer can be selected at a time
  - Selected transfer is highlighted in primary color
  - System automatically maps transfers to detected lanes:
    - T100 → Upper lane (index 0)
    - T200 → Middle lane (index 1)  
    - T300 → Lower lane (index 2)

### 3. State Selection
- **Purpose**: Define the current state of the selected transfer
- **Options**: 
  - **OK**: Transfer is operating normally (green indicator)
  - **OBSTÁCULO**: Transfer is stopped due to obstacle (red indicator)
  - **FALLO**: Transfer is in failure state (orange indicator)
- **Behavior**: Visual color coding helps identify current state selection

### 4. Current Selection Display
- **Purpose**: Shows the currently selected transfer and state combination
- **Content**: 
  - "Transfer: T[100|200|300]"
  - "State: [OK|OBSTÁCULO|FALLO]" with color-coded text
- **Use**: Confirms what will be captured before pressing "Capturar plantilla"

### 5. Template Preview (After Capture)
- **Purpose**: Provides visual confirmation of the captured template
- **Appears**: Only after a successful template capture
- **Content**:
  - Green "Template Captured" header
  - Transfer and state information
  - Detected region coordinates (left,top to right,bottom)
  - Preview image of the cropped template
- **Use**: Allows operator to verify the correct region was captured

### 6. Action Buttons

#### "Capturar plantilla" (Capture Template)
- **Purpose**: Capture a template for the current transfer/state selection
- **Enabled**: When not currently capturing and a valid state is selected
- **Behavior**:
  1. Detects lanes in the current camera frame
  2. Selects the appropriate lane based on transfer selection
  3. Crops the image to the detected lane region
  4. Saves the cropped template
  5. Updates the preview and progress
  6. Shows success message with region coordinates

#### "Guardar" (Save)
- **Purpose**: Confirm and save the current training session
- **Enabled**: Only when a template has been captured for the current transfer/state selection
- **Behavior**: 
  - Validates that the captured template matches current selection
  - Shows training progress message
  - Returns to main activity with RESULT_OK

#### "Cancelar" (Cancel)
- **Purpose**: Cancel current capture or exit training mode
- **Behavior**:
  - If template preview is shown: Clears the preview and resets capture state
  - Otherwise: Exits training mode without saving

## Training Workflow Steps

### Step 1: Setup
1. Position the phone to capture all three transfer lanes
2. Ensure proper lighting and clear view of the lanes
3. Verify that lane detection is working (lanes should be detected automatically)

### Step 2: Capture Templates
For each transfer/state combination (9 total):

1. **Select Transfer**: Choose T100, T200, or T300
2. **Select State**: Choose the current state (OK, OBSTÁCULO, or FALLO)
3. **Position Transfer**: Ensure the transfer shows the selected state
4. **Capture**: Press "Capturar plantilla"
5. **Verify**: Check the template preview shows the correct region
6. **Save**: Press "Guardar" to confirm the capture

### Step 3: Complete Training
- Continue until all 9 templates are captured (3 transfers × 3 states)
- Progress indicator shows completion status
- System automatically prevents duplicate captures

## Lane Detection and Region Mapping

### Automatic Lane Detection
- The system uses computer vision to detect lane boundaries
- Lanes are detected once and cached for consistency
- Each lane corresponds to a specific transfer region

### Transfer-to-Lane Mapping
```
T100 (Upper)   → Lane 0 → Top region of camera view
T200 (Middle)  → Lane 1 → Middle region of camera view  
T300 (Lower)   → Lane 2 → Bottom region of camera view
```

### Region Cropping
- Templates are automatically cropped to the detected lane region
- Cropping ensures consistent template sizes and positions
- Region coordinates are displayed for verification

## Error Handling

### Common Issues and Solutions

1. **"No hay imagen disponible para captura"**
   - Camera not ready or no frame available
   - Solution: Wait a moment and try again

2. **"Error: No se pudieron detectar las vías"**
   - Lane detection failed
   - Solution: Adjust camera position, ensure clear view of lanes, check lighting

3. **"Error: Vía no detectada"**
   - Selected transfer lane not found
   - Solution: Verify all three lanes are visible in camera view

4. **"Error al recortar la región"**
   - Image cropping failed
   - Solution: Check camera resolution and try again

## Logging

The training workflow includes comprehensive logging:

- **Capture Start**: `Template capture started for T[transfer] [state]`
- **Lane Detection**: `Successfully detected [N] lanes`
- **Region Selection**: `Selected lane [index] for T[transfer]: [coordinates]`
- **Success**: `Template saved successfully for T[transfer] [state]`
- **Error**: Detailed error messages for troubleshooting

## Best Practices

1. **Consistent Positioning**: Always capture from the same camera angle and distance
2. **Clear States**: Ensure each state (OK, OBSTÁCULO, FALLO) is clearly visible
3. **Verify Previews**: Always check the template preview before saving
4. **Complete All Templates**: Capture all 9 templates for optimal classification accuracy
5. **Recalibrate if Needed**: Use "Recalibrar vías" if lane detection becomes inaccurate

## Technical Details

### Template Storage
- Templates are saved as PNG images (128×64 pixels)
- Stored in app's private directory under `templates/`
- Naming convention: `tpl_t[transfer]_[state].png`
- Manifest file tracks which templates are available

### Image Processing
- YUV_420_888 format conversion to RGB bitmap
- Automatic region cropping based on lane detection
- Template resizing to standard dimensions
- Grayscale processing for classification

### State Management
- Current selection tracked in UI state
- Preview state managed separately from capture state
- Progress persisted across app sessions
- Validation ensures capture matches current selection