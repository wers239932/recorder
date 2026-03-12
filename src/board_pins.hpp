// === Display (LCD) ===
// LCD использует свой собственный набор пинов SPI
#define PIN_LCD_SCLK   7
#define PIN_LCD_MOSI   6
#define PIN_LCD_MISO   2   // Этот пин физически относится только к LCD
#define PIN_LCD_CS     14
#define PIN_LCD_DC     15
#define PIN_LCD_RST    21
#define PIN_LCD_BL     22

// === SD Card (Отдельный SPI интерфейс) ===
// SD карта подключена к ДРУГИМ пинам!
#define PIN_SD_CS      4   // !!! ИСПРАВЛЕНО: Было 13, надо 4 [citation:8]
#define PIN_SD_SCLK    7   // SCK может быть общим (это нормально для шины)
#define PIN_SD_MOSI    6   // MOSI может быть общим
#define PIN_SD_MISO    5   // !!! ИСПРАВЛЕНО: Было 2, надо 5 [citation:8]

#define PIN_I2S_BCLK   1   // BCLK пин

#ifndef PIN_I2S_WS
#define PIN_I2S_WS     3   // Word Select пин
#endif

#ifndef PIN_I2S_DIN
#define PIN_I2S_DIN    4   // Data In пин
#endif