import 'package:flutter/material.dart';
import 'package:google_fonts/google_fonts.dart';
import '../models/trade_history.dart';
import '../screens/api_settings_screen.dart';

/// КАРТОЧКА "АНАЛИЗ РЫНКА" (БЕЗ ХАРДКОДА ДАННЫХ)
/// Показывает:
/// - Предупреждение, если пара не выбрана
/// - Скелетон/индикатор загрузки при первом расчёте свечей klines
/// - Текст ошибки Binance API при сбое получения klines
/// - Реальные индикаторы (RSI, EMA, MACD, Volume, Bollinger), вычисленные из klines
/// - Реальный торговый сигнал и Score (0-100%)
class MarketAnalysisCard extends StatelessWidget {
  final SignalData? signal;
  final bool isAnalyzing;
  final String? errorMessage;
  final StrategyRiskConfig config;
  final VoidCallback onRetry;
  final Function(StrategyRiskConfig) onConfigChanged;

  const MarketAnalysisCard({
    Key? key,
    required this.signal,
    required this.isAnalyzing,
    this.errorMessage,
    required this.config,
    required this.onRetry,
    required this.onConfigChanged,
  }) : super(key: key);

  @override
  Widget build(BuildContext context) {
    final ind = signal?.indicators;
    final score = signal?.score ?? 0;
    final action = signal?.action ?? SignalAction.hold;

    Color actionColor = const Color(0xFF7E9BB8);
    String actionTitle = 'HOLD / ОЖИДАНИЕ СИГНАЛА';
    if (action == SignalAction.buyLong) {
      actionColor = const Color(0xFF00E676);
      actionTitle = 'СИГНАЛ: BUY (LONG SPOT)';
    } else if (action == SignalAction.sellSpot) {
      actionColor = const Color(0xFFFF5252);
      actionTitle = 'СИГНАЛ: SELL (ФИКСАЦИЯ СПОТА)';
    }

    return HudCard(
      borderColor: errorMessage != null
          ? const Color(0xFFFF5252)
          : (action == SignalAction.buyLong
              ? const Color(0xFF00E676)
              : (action == SignalAction.sellSpot ? const Color(0xFFFF5252) : const Color(0xFF00D4FF))),
      glowColor: errorMessage != null
          ? const Color(0x22FF5252)
          : (action == SignalAction.buyLong
              ? const Color(0x2200E676)
              : (action == SignalAction.sellSpot ? const Color(0x22FF5252) : const Color(0x2200D4FF))),
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            // Заголовок и интервал свечей
            Row(
              mainAxisAlignment: MainAxisAlignment.spaceBetween,
              children: [
                Row(
                  children: [
                    const Icon(Icons.analytics_outlined, color: Color(0xFF00D4FF), size: 18),
                    const SizedBox(width: 8),
                    Text(
                      'АНАЛИЗ РЫНКА // ИНДИКАТОРЫ',
                      style: GoogleFonts.orbitron(
                        color: const Color(0xFF00D4FF),
                        fontSize: 12,
                        fontWeight: FontWeight.bold,
                        letterSpacing: 1.1,
                      ),
                    ),
                  ],
                ),
                // Выбор таймфрейма klines (1m, 5m, 15m, 1h)
                Row(
                  children: ['1m', '5m', '15m', '1h'].map((intvl) {
                    final isSel = config.interval == intvl;
                    return InkWell(
                      onTap: () => onConfigChanged(config.copyWith(interval: intvl)),
                      child: Container(
                        margin: const EdgeInsets.only(left: 4),
                        padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 2),
                        decoration: BoxDecoration(
                          color: isSel ? const Color(0xFF00D4FF) : const Color(0x1A00D4FF),
                          borderRadius: BorderRadius.circular(4),
                          border: Border.all(
                            color: isSel ? const Color(0xFF00D4FF) : const Color(0x3300D4FF),
                          ),
                        ),
                        child: Text(
                          intvl,
                          style: GoogleFonts.orbitron(
                            color: isSel ? const Color(0xFF0A1628) : Colors.white,
                            fontSize: 10,
                            fontWeight: FontWeight.bold,
                          ),
                        ),
                      ),
                    );
                  }).toList(),
                ),
              ],
            ),
            const SizedBox(height: 14),

            // 1. СОСТОЯНИЕ: Ошибка загрузки свечей с Binance
            if (errorMessage != null) ...[
              Container(
                width: double.infinity,
                padding: const EdgeInsets.all(14),
                decoration: BoxDecoration(
                  color: const Color(0x1AFF1744),
                  borderRadius: BorderRadius.circular(6),
                  border: Border.all(color: const Color(0x66FF1744)),
                ),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Row(
                      children: [
                        const Icon(Icons.error_outline, color: Color(0xFFFF5252), size: 18),
                        const SizedBox(width: 8),
                        Text(
                          'ОШИБКА РАСЧЁТА ИНДИКАТОРОВ',
                          style: GoogleFonts.orbitron(
                            color: const Color(0xFFFF5252),
                            fontSize: 11,
                            fontWeight: FontWeight.bold,
                          ),
                        ),
                      ],
                    ),
                    const SizedBox(height: 6),
                    Text(
                      errorMessage!,
                      style: GoogleFonts.rajdhani(color: const Color(0xFFFF8A80), fontSize: 12),
                    ),
                    const SizedBox(height: 8),
                    HudButton(
                      text: 'ПОВТОРИТЬ АНАЛИЗ KLINES',
                      icon: Icons.refresh,
                      color: const Color(0xFFFF5252),
                      isSmall: true,
                      isOutlined: true,
                      onPressed: onRetry,
                    ),
                  ],
                ),
              ),
            ]
            // 2. СОСТОЯНИЕ: Первая загрузка / анализ klines
            else if (isAnalyzing && ind == null) ...[
              Padding(
                padding: const EdgeInsets.symmetric(vertical: 24),
                child: Center(
                  child: Column(
                    children: [
                      const CircularProgressIndicator(strokeWidth: 2.5, color: Color(0xFF00D4FF)),
                      const SizedBox(height: 10),
                      Text(
                        'Загрузка свечей klines и расчёт индикаторов...',
                        style: GoogleFonts.rajdhani(color: const Color(0xFF7E9BB8), fontSize: 12),
                      ),
                    ],
                  ),
                ),
              ),
            ]
            // 3. СОСТОЯНИЕ: Реальные данные рассчитаны
            else if (ind != null) ...[
              // Большой бейдж торгового сигнала и Score
              Container(
                padding: const EdgeInsets.all(12),
                decoration: BoxDecoration(
                  color: actionColor.withOpacity(0.12),
                  borderRadius: BorderRadius.circular(8),
                  border: Border.all(color: actionColor, width: 1.5),
                ),
                child: Row(
                  mainAxisAlignment: MainAxisAlignment.spaceBetween,
                  children: [
                    Expanded(
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Text(
                            actionTitle,
                            style: GoogleFonts.orbitron(
                              color: actionColor,
                              fontSize: 13,
                              fontWeight: FontWeight.bold,
                              letterSpacing: 1.1,
                            ),
                          ),
                          const SizedBox(height: 3),
                          Text(
                            'Порог: ${config.minScoreThreshold}% | SL: ${config.stopLossPercent}% | TP: ${config.takeProfitPercent}%',
                            style: GoogleFonts.rajdhani(color: const Color(0xFF7E9BB8), fontSize: 11),
                          ),
                        ],
                      ),
                    ),
                    Container(
                      padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 6),
                      decoration: BoxDecoration(
                        color: const Color(0xFF071220),
                        borderRadius: BorderRadius.circular(6),
                        border: Border.all(color: actionColor),
                      ),
                      child: Column(
                        children: [
                          Text(
                            'SCORE',
                            style: GoogleFonts.orbitron(color: const Color(0xFF7E9BB8), fontSize: 9),
                          ),
                          Text(
                            '$score%',
                            style: GoogleFonts.orbitron(
                              color: actionColor,
                              fontSize: 16,
                              fontWeight: FontWeight.bold,
                            ),
                          ),
                        ],
                      ),
                    ),
                  ],
                ),
              ),
              const SizedBox(height: 14),

              // Сетка индикаторов
              Row(
                children: [
                  // RSI
                  Expanded(
                    child: _buildIndicatorTile(
                      label: 'RSI (14)',
                      value: ind.rsi.toStringAsFixed(1),
                      status: ind.rsi <= config.rsiOversold
                          ? 'ПЕРЕПРОДАН'
                          : (ind.rsi >= config.rsiOverbought ? 'ПЕРЕКУПЛЕН' : 'НЕЙТРАЛЬНО'),
                      statusColor: ind.rsi <= config.rsiOversold
                          ? const Color(0xFF00E676)
                          : (ind.rsi >= config.rsiOverbought
                              ? const Color(0xFFFF5252)
                              : const Color(0xFF7E9BB8)),
                    ),
                  ),
                  const SizedBox(width: 8),
                  // EMA CROSS
                  Expanded(
                    child: _buildIndicatorTile(
                      label: 'EMA 9 / 21',
                      value: '${ind.emaFast.toStringAsFixed(1)} / ${ind.emaSlow.toStringAsFixed(1)}',
                      status: ind.isEmaBullish ? 'BULLISH (9>21)' : 'BEARISH (9<21)',
                      statusColor: ind.isEmaBullish ? const Color(0xFF00E676) : const Color(0xFFFF5252),
                    ),
                  ),
                ],
              ),
              const SizedBox(height: 8),
              Row(
                children: [
                  // MACD
                  Expanded(
                    child: _buildIndicatorTile(
                      label: 'MACD (12,26,9)',
                      value: 'H: ${ind.macdHist >= 0 ? "+" : ""}${ind.macdHist.toStringAsFixed(2)}',
                      status: ind.isMacdRising ? 'РАСТУЩИЙ ИМПУЛЬС' : 'ПАДАЮЩИЙ ИМПУЛЬС',
                      statusColor: ind.isMacdRising ? const Color(0xFF00E676) : const Color(0xFFFF5252),
                    ),
                  ),
                  const SizedBox(width: 8),
                  // VOLUME
                  Expanded(
                    child: _buildIndicatorTile(
                      label: 'VOLUME vs AVG(20)',
                      value: '${ind.currentVolume.toStringAsFixed(1)} / ${ind.avgVolume.toStringAsFixed(1)}',
                      status: ind.isVolumeAboveAvg ? 'ВЫШЕ СРЕДНЕГО' : 'НИЖЕ СРЕДНЕГО',
                      statusColor: ind.isVolumeAboveAvg ? const Color(0xFF00E676) : const Color(0xFFFFB300),
                    ),
                  ),
                ],
              ),
              const SizedBox(height: 8),
              // BOLLINGER BANDS
              Container(
                padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 8),
                decoration: BoxDecoration(
                  color: const Color(0xFF071220),
                  borderRadius: BorderRadius.circular(6),
                  border: Border.all(color: const Color(0x2200D4FF)),
                ),
                child: Row(
                  mainAxisAlignment: MainAxisAlignment.spaceBetween,
                  children: [
                    Text(
                      'BOLLINGER (20,2)',
                      style: GoogleFonts.orbitron(color: const Color(0xFF7E9BB8), fontSize: 10),
                    ),
                    Text(
                      'L: ${ind.bbLower.toStringAsFixed(1)} | M: ${ind.bbMiddle.toStringAsFixed(1)} | U: ${ind.bbUpper.toStringAsFixed(1)}',
                      style: GoogleFonts.rajdhani(
                        color: const Color(0xFFFF8A65),
                        fontSize: 12,
                        fontWeight: FontWeight.bold,
                      ),
                    ),
                  ],
                ),
              ),

              // Совпавшие условия стратегии
              if (signal != null && signal!.matchedConditions.isNotEmpty) ...[
                const SizedBox(height: 12),
                Text(
                  'СОВПАВШИЕ УСЛОВИЯ СТРАТЕГИИ:',
                  style: GoogleFonts.orbitron(color: const Color(0xFF7E9BB8), fontSize: 9, letterSpacing: 0.8),
                ),
                const SizedBox(height: 4),
                ...signal!.matchedConditions.map((cond) => Padding(
                  padding: const EdgeInsets.symmetric(vertical: 2),
                  child: Row(
                    children: [
                      const Icon(Icons.check_circle_outline, color: Color(0xFF00D4FF), size: 14),
                      const SizedBox(width: 6),
                      Expanded(
                        child: Text(
                          cond,
                          style: GoogleFonts.rajdhani(color: Colors.white, fontSize: 12),
                        ),
                      ),
                    ],
                  ),
                )),
              ],

              // Рекомендуемые уровни Stop-Loss / Take-Profit
              if (signal != null && signal!.action != SignalAction.hold) ...[
                const SizedBox(height: 12),
                Container(
                  padding: const EdgeInsets.all(8),
                  decoration: BoxDecoration(
                    color: const Color(0x1A00D4FF),
                    borderRadius: BorderRadius.circular(6),
                    border: Border.all(color: const Color(0x3300D4FF)),
                  ),
                  child: Row(
                    mainAxisAlignment: MainAxisAlignment.spaceBetween,
                    children: [
                      Text(
                        'STOP-LOSS: ${signal!.recommendedStopLoss.toStringAsFixed(2)} USDT (-${config.stopLossPercent}%)',
                        style: GoogleFonts.rajdhani(
                          color: const Color(0xFFFF5252),
                          fontWeight: FontWeight.bold,
                          fontSize: 11,
                        ),
                      ),
                      Text(
                        'TAKE-PROFIT: ${signal!.recommendedTakeProfit.toStringAsFixed(2)} USDT (+${config.takeProfitPercent}%)',
                        style: GoogleFonts.rajdhani(
                          color: const Color(0xFF00E676),
                          fontWeight: FontWeight.bold,
                          fontSize: 11,
                        ),
                      ),
                    ],
                  ),
                ),
              ],
            ] else ...[
              Padding(
                padding: const EdgeInsets.symmetric(vertical: 20),
                child: Center(
                  child: Text(
                    'Выберите торговую пару для запуска анализа индикаторов',
                    style: GoogleFonts.rajdhani(color: const Color(0xFF7E9BB8), fontSize: 12),
                  ),
                ),
              ),
            ],
          ],
        ),
      ),
    );
  }

  Widget _buildIndicatorTile({
    required String label,
    required String value,
    required String status,
    required Color statusColor,
  }) {
    return Container(
      padding: const EdgeInsets.all(8),
      decoration: BoxDecoration(
        color: const Color(0xFF071220),
        borderRadius: BorderRadius.circular(6),
        border: Border.all(color: const Color(0x2200D4FF)),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(
            label,
            style: GoogleFonts.orbitron(color: const Color(0xFF7E9BB8), fontSize: 9),
          ),
          const SizedBox(height: 2),
          Text(
            value,
            style: GoogleFonts.rajdhani(
              color: Colors.white,
              fontSize: 13,
              fontWeight: FontWeight.bold,
            ),
          ),
          const SizedBox(height: 2),
          Text(
            status,
            style: GoogleFonts.orbitron(
              color: statusColor,
              fontSize: 9,
              fontWeight: FontWeight.bold,
            ),
          ),
        ],
      ),
    );
  }
}
