import 'package:flutter/material.dart';
import 'package:fl_chart/fl_chart.dart';
import 'package:google_fonts/google_fonts.dart';
import '../screens/api_settings_screen.dart';

/// КАРТОЧКА БАЛАНСА (БЕЗ ХАРДКОДА ДАННЫХ)
/// Показывает:
/// - Предупреждение со ссылкой в настройки, если ключи не заданы
/// - Скелетон/индикатор загрузки, пока идёт запрос к Binance REST API
/// - Текст ошибки Binance (error.msg), если запрос завершился сбоем
/// - Реальные данные Spot USDT (Total, Free, Locked) и динамику сессии
class BalanceCard extends StatelessWidget {
  final double? totalUsdt;
  final double? freeUsdt;
  final double? lockedUsdt;
  final List<FlSpot> balanceHistory;
  final bool hasCredentials;
  final bool isLoading;
  final String? errorMessage;
  final VoidCallback onRefresh;
  final VoidCallback onOpenSettings;

  const BalanceCard({
    Key? key,
    required this.totalUsdt,
    required this.freeUsdt,
    required this.lockedUsdt,
    required this.balanceHistory,
    required this.hasCredentials,
    required this.isLoading,
    required this.errorMessage,
    required this.onRefresh,
    required this.onOpenSettings,
  }) : super(key: key);

  @override
  Widget build(BuildContext context) {
    return HudCard(
      borderColor: !hasCredentials
          ? const Color(0xFFFFB300)
          : (errorMessage != null ? const Color(0xFFFF5252) : const Color(0xFF00D4FF)),
      glowColor: !hasCredentials
          ? const Color(0x22FFB300)
          : (errorMessage != null ? const Color(0x22FF5252) : const Color(0x2200D4FF)),
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            // Заголовок карточки
            Row(
              mainAxisAlignment: MainAxisAlignment.spaceBetween,
              children: [
                Row(
                  children: [
                    Icon(
                      Icons.account_balance_wallet_outlined,
                      color: !hasCredentials
                          ? const Color(0xFFFFB300)
                          : (errorMessage != null ? const Color(0xFFFF5252) : const Color(0xFF00D4FF)),
                      size: 18,
                    ),
                    const SizedBox(width: 8),
                    Text(
                      'БАЛАНС СПОТ (USDT)',
                      style: GoogleFonts.orbitron(
                        color: !hasCredentials
                            ? const Color(0xFFFFB300)
                            : (errorMessage != null ? const Color(0xFFFF5252) : const Color(0xFF00D4FF)),
                        fontSize: 12,
                        fontWeight: FontWeight.bold,
                        letterSpacing: 1.1,
                      ),
                    ),
                  ],
                ),
                if (isLoading)
                  const SizedBox(
                    width: 16,
                    height: 16,
                    child: CircularProgressIndicator(strokeWidth: 2, color: Color(0xFF00D4FF)),
                  )
                else
                  IconButton(
                    icon: const Icon(Icons.refresh, color: Color(0xFF00D4FF), size: 18),
                    onPressed: onRefresh,
                    tooltip: 'Обновить баланс',
                    padding: EdgeInsets.zero,
                    constraints: const BoxConstraints(),
                  ),
              ],
            ),
            const SizedBox(height: 12),

            // 1. СОСТОЯНИЕ: Ключи не настроены
            if (!hasCredentials) ...[
              Container(
                width: double.infinity,
                padding: const EdgeInsets.all(14),
                decoration: BoxDecoration(
                  color: const Color(0x1AFFB300),
                  borderRadius: BorderRadius.circular(6),
                  border: Border.all(color: const Color(0x66FFB300)),
                ),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Row(
                      children: [
                        const Icon(Icons.warning_amber_rounded, color: Color(0xFFFFB300), size: 20),
                        const SizedBox(width: 8),
                        Text(
                          '⚠ API-КЛЮЧИ НЕ НАСТРОЕНЫ',
                          style: GoogleFonts.orbitron(
                            color: const Color(0xFFFFB300),
                            fontSize: 11,
                            fontWeight: FontWeight.bold,
                          ),
                        ),
                      ],
                    ),
                    const SizedBox(height: 6),
                    Text(
                      'Для получения реального баланса Binance Spot Testnet укажите API Key и Secret Key.',
                      style: GoogleFonts.rajdhani(color: const Color(0xFFB0C4DE), fontSize: 12),
                    ),
                    const SizedBox(height: 10),
                    HudButton(
                      text: 'ПЕРЕЙТИ В НАСТРОЙКИ API',
                      icon: Icons.vpn_key_outlined,
                      color: const Color(0xFFFFB300),
                      isSmall: true,
                      onPressed: onOpenSettings,
                    ),
                  ],
                ),
              ),
            ]
            // 2. СОСТОЯНИЕ: Ошибка запроса к API Binance
            else if (errorMessage != null) ...[
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
                          'ОШИБКА BINANCE API',
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
                      text: 'ПОВТОРИТЬ ЗАПРОС',
                      icon: Icons.refresh,
                      color: const Color(0xFFFF5252),
                      isSmall: true,
                      isOutlined: true,
                      onPressed: onRefresh,
                    ),
                  ],
                ),
              ),
            ]
            // 3. СОСТОЯНИЕ: Идёт загрузка данных (skeleton/spinner)
            else if (isLoading && totalUsdt == null) ...[
              Padding(
                padding: const EdgeInsets.symmetric(vertical: 24),
                child: Center(
                  child: Column(
                    children: [
                      const CircularProgressIndicator(strokeWidth: 2.5, color: Color(0xFF00D4FF)),
                      const SizedBox(height: 10),
                      Text(
                        'Загрузка баланса с Binance Spot Testnet...',
                        style: GoogleFonts.rajdhani(color: const Color(0xFF7E9BB8), fontSize: 12),
                      ),
                    ],
                  ),
                ),
              ),
            ]
            // 4. СОСТОЯНИЕ: Реальные данные получены от API
            else if (totalUsdt != null && freeUsdt != null && lockedUsdt != null) ...[
              // Основная сумма
              Row(
                crossAxisAlignment: CrossAxisAlignment.baseline,
                textBaseline: TextBaseline.alphabetic,
                children: [
                  Text(
                    totalUsdt!.toStringAsFixed(2),
                    style: GoogleFonts.rajdhani(
                      color: const Color(0xFFFF8A65),
                      fontSize: 32,
                      fontWeight: FontWeight.bold,
                      letterSpacing: 1.2,
                    ),
                  ),
                  const SizedBox(width: 6),
                  Text(
                    'USDT',
                    style: GoogleFonts.orbitron(
                      color: const Color(0xFF7E9BB8),
                      fontSize: 14,
                      fontWeight: FontWeight.w600,
                    ),
                  ),
                ],
              ),
              const SizedBox(height: 12),

              // Свободно / В ордерах
              Row(
                children: [
                  Expanded(
                    child: Container(
                      padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 8),
                      decoration: BoxDecoration(
                        color: const Color(0x1A00E676),
                        borderRadius: BorderRadius.circular(6),
                        border: Border.all(color: const Color(0x3300E676), width: 1),
                      ),
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Text(
                            'СВОБОДНО',
                            style: GoogleFonts.orbitron(
                              color: const Color(0xFF00E676),
                              fontSize: 10,
                              letterSpacing: 0.8,
                            ),
                          ),
                          const SizedBox(height: 2),
                          Text(
                            '${freeUsdt!.toStringAsFixed(2)} USDT',
                            style: GoogleFonts.rajdhani(
                              color: Colors.white,
                              fontSize: 14,
                              fontWeight: FontWeight.bold,
                            ),
                          ),
                        ],
                      ),
                    ),
                  ),
                  const SizedBox(width: 10),
                  Expanded(
                    child: Container(
                      padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 8),
                      decoration: BoxDecoration(
                        color: const Color(0x1AFFB300),
                        borderRadius: BorderRadius.circular(6),
                        border: Border.all(color: const Color(0x33FFB300), width: 1),
                      ),
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Text(
                            'В ОРДЕРАХ',
                            style: GoogleFonts.orbitron(
                              color: const Color(0xFFFFB300),
                              fontSize: 10,
                              letterSpacing: 0.8,
                            ),
                          ),
                          const SizedBox(height: 2),
                          Text(
                            '${lockedUsdt!.toStringAsFixed(2)} USDT',
                            style: GoogleFonts.rajdhani(
                              color: Colors.white,
                              fontSize: 14,
                              fontWeight: FontWeight.bold,
                            ),
                          ),
                        ],
                      ),
                    ),
                  ),
                ],
              ),

              const SizedBox(height: 16),

              // График динамики
              Row(
                mainAxisAlignment: MainAxisAlignment.spaceBetween,
                children: [
                  Text(
                    'ДИНАМИКА БАЛАНСА (СЕССИЯ)',
                    style: GoogleFonts.orbitron(
                      color: const Color(0xFF7E9BB8),
                      fontSize: 10,
                      letterSpacing: 1,
                    ),
                  ),
                  Text(
                    '${balanceHistory.length} точек',
                    style: GoogleFonts.rajdhani(
                      color: const Color(0xFF00D4FF),
                      fontSize: 11,
                    ),
                  ),
                ],
              ),
              const SizedBox(height: 8),

              SizedBox(
                height: 70,
                child: balanceHistory.isEmpty
                    ? Center(
                        child: Text(
                          'Накапливаются данные сессии...',
                          style: GoogleFonts.rajdhani(color: const Color(0xFF536D88), fontSize: 11),
                        ),
                      )
                    : LineChart(
                        LineChartData(
                          gridData: FlGridData(
                            show: true,
                            drawVerticalLine: false,
                            horizontalInterval: 100,
                            getDrawingHorizontalLine: (value) => const FlLine(
                              color: Color(0x1A00D4FF),
                              strokeWidth: 1,
                            ),
                          ),
                          titlesData: const FlTitlesData(show: false),
                          borderData: FlBorderData(show: false),
                          minX: 0,
                          maxX: (balanceHistory.length > 1 ? balanceHistory.length - 1 : 1).toDouble(),
                          lineBarsData: [
                            LineChartBarData(
                              spots: balanceHistory,
                              isCurved: true,
                              curveSmoothness: 0.35,
                              color: const Color(0xFF00D4FF),
                              barWidth: 2,
                              isStrokeCapRound: true,
                              dotData: const FlDotData(show: false),
                              belowBarData: BarAreaData(
                                show: true,
                                gradient: LinearGradient(
                                  begin: Alignment.topCenter,
                                  end: Alignment.bottomCenter,
                                  colors: [
                                    const Color(0xFF00D4FF).withOpacity(0.3),
                                    const Color(0xFF00D4FF).withOpacity(0.0),
                                  ],
                                ),
                              ),
                            ),
                          ],
                        ),
                      ),
              ),
            ] else ...[
              Padding(
                padding: const EdgeInsets.symmetric(vertical: 20),
                child: Center(
                  child: Text(
                    'Нет данных баланса. Нажмите "Обновить".',
                    style: GoogleFonts.rajdhani(color: const Color(0xFF7E9BB8), fontSize: 13),
                  ),
                ),
              ),
            ],
          ],
        ),
      ),
    );
  }
}
