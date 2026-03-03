# Mindaplus — Iteraciones

## Iteración 1 (v0.1) — Núcleo Android funcionando
Objetivo: detectar OBSTÁCULO por color naranja en 3 ROIs fijas y notificar por Telegram con anti-spam por transición.

Entrega mínima:
- App Android (Kotlin) con:
  - Vista previa de cámara en pantalla (landscape)
  - Loop de análisis cada 5 segundos
  - 3 ROIs fijas definidas en código (coordenadas relativas)
  - Detección de naranja (HSV o equivalente)
  - Memoria de estado por transfer (OK/OBSTÁCULO)
  - Envío de mensajes Telegram usando token + chat_id
- Pantalla simple para introducir:
  - Telegram Bot Token
  - Telegram Chat ID
  - Botón “Start/Stop vigilancia”
- Logs visibles (Logcat) de:
  - Estado actual de cada transfer
  - Mensajes enviados

## Iteración 2 (v0.2) — Robustez y ajustes rápidos
- Ajuste de sensibilidad/umbral de naranja desde UI (sliders)
- Ajuste fino de ROIs por porcentajes desde UI (sin dibujar)
- Modo debug: guardar frame cuando hay cambio

## Iteración 3 (v0.3) — Estados extra
- Añadir detección de más estados (pendiente de definir)
- Mensajes más ricos y/o adjuntar captura