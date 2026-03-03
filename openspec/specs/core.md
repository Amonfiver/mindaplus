# Mindaplus — OpenSpec v0.1 (Android)

## 1) Objetivo
Crear una app Android (Kotlin) que vigile visualmente el estado de 3 transfers (Transfer 100/200/300) usando la cámara del móvil en modo landscape, detecte el estado OBSTÁCULO cuando un transfer se muestre en color naranja, y notifique por Telegram cuando un transfer se detiene por obstáculo y cuando se rearma.

## 2) Contexto del sistema observado
- Existe un sistema de almacenamiento con 3 transfers:
  - Transfer 100
  - Transfer 200
  - Transfer 300
- Cada transfer puede detenerse si una fotocélula detecta un obstáculo.
- Cuando hay obstáculo, el transfer cambia visualmente a color naranja (indicador de parada por obstáculo).
- En v0.1, cualquier color distinto de naranja se considera OK.

## 3) Definiciones de estado (v0.1)
- OK: el transfer NO está en color naranja.
- OBSTÁCULO: el transfer está en color naranja.

## 4) Restricciones (empresa / operación)
- La solución NO debe depender del ordenador observado (no USB, no software en el PC).
- La detección debe hacerse SOLO con la cámara del teléfono.
- La orientación de uso es landscape (teléfono colocado horizontal en trípode).

## 5) Entrada (cámara)
- La app usa la cámara del teléfono para capturar frames del entorno.
- El análisis se realiza cada 5 segundos (configurable a futuro; en v0.1 fijo a 5s).

## 6) Zonas de análisis (ROIs fijas, sin calibración)
- NO habrá modo de “dibujar ROIs”.
- Las zonas a vigilar son regiones fijas y fácilmente identificables en pantalla:
  - Franjas horizontales (vías) blancas con dos líneas negras horizontales.
  - Sobre esas vías se desplazan los transfers.
- En v0.1 se definen 3 ROIs fijas (una por transfer) en coordenadas relativas (0..1) respecto al frame analizado.
  - ROI_T100
  - ROI_T200
  - ROI_T300

## 7) Detección de color (naranja)
- Para cada ROI, se calcula si el transfer está en estado OBSTÁCULO detectando presencia suficiente de “naranja”.
- Regla base (v0.1):
  - Si la proporción de píxeles “naranja” en la ROI supera un umbral => OBSTÁCULO
  - Si no => OK
- La implementación debe usar un método robusto y común:
  - Convertir a espacio HSV (o aproximación equivalente) y aplicar rangos de naranja.
  - Umbral de proporción configurable internamente (en v0.1 puede ser constante razonable).
- Se asume que las ROIs están bien colocadas para que dentro aparezca el transfer cuando pasa por esa zona.

## 8) Salida (Telegram)
- Se enviarán notificaciones a un chat/usuario mediante un bot de Telegram.
- Disponemos de token y chat_id (se configurarán en la app).

Mensajes:
- Transición OK -> OBSTÁCULO:
  - "Transfer {X} parado, obstáculo en la vía."
- Transición OBSTÁCULO -> OK:
  - "Transfer {X} rearmado, todo OK."

## 9) Reglas anti-spam (muy importante)
- Solo se envía mensaje cuando hay CAMBIO de estado por transfer.
- Si un transfer permanece en OBSTÁCULO, NO repetir el mismo mensaje cada 5s.
- Si un transfer pasa a OK, se envía 1 mensaje de rearme y luego no se repite.
- Los transfers son independientes:
  - Si se pone naranja el 200 y luego el 100, se deben enviar alertas separadas (una por cada transfer) siguiendo las reglas anti-spam.

## 10) Fuera de alcance (por ahora)
- Detección de otros estados (FALLO, AUTO, etc.)
- Detección de zona exacta del fallo (izquierda/derecha)
- Entrenamiento por plantillas o ML
- Envío de imagen adjunta por Telegram
- Calibración manual de ROIs
- Panel avanzado de configuración (se anunciará, pero no se implementa en v0.1)

## 11) Funcionalidades anunciadas (NO implementadas en v0.1)
- Pantalla de configuración para:
  - Intervalo de análisis
  - Umbrales de naranja
  - Ajustes finos de ROIs (sin dibujar, solo sliders/porcentajes)
- Modo debug: guardar frame cuando hay cambio de estado
- Adjuntar captura en la alerta Telegram

## 12) Criterios de aceptación (v0.1)
- La app funciona en Android (Kotlin) en landscape.
- Captura y analiza cada 5 segundos.
- Evalúa 3 ROIs fijas (T100/T200/T300).
- Clasifica cada transfer como OK u OBSTÁCULO según detección de naranja.
- Envía Telegram SOLO en transiciones:
  - OK->OBSTÁCULO: mensaje de “parado”
  - OBSTÁCULO->OK: mensaje de “rearmado”
- Permite que dos transfers alerten de forma independiente sin spam.