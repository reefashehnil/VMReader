# VM Reader

WhatsApp Bangla voice message → Bangla text, fully on the phone (whisper.cpp + fine-tuned Whisper-small).

1. GitHub builds the APK (Actions tab → latest run → Artifacts → VMReader-APK).
2. Install the APK on the phone.
3. Put `ggml-bn-small-q5_0.bin` on the phone, open the app, tap **Load model**, choose the file.
4. In WhatsApp: long-press a voice message → Share → **VM Reader**.
