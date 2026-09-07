import 'package:flutter/material.dart';
import 'package:google_fonts/google_fonts.dart';
import 'package:intl/intl.dart';
import '../models/market_models.dart';
import '../screens/api_settings_screen.dart';

/// КАРТОЧКА ОТКРЫТЫХ ОРДЕРОВ / ПОЗИЦИЙ
/// - Список активных ордеров с парой, стороной (BUY/SELL), ценой, количеством, статусом
/// - Кнопка отмены каждого ордера
class OpenOrdersCard extends StatelessWidget {
  final List<OpenOrder> orders;
  final bool isLoading;
  final VoidCallback onRefresh;
  final Function(OpenOrder) onCancelOrder;

  const OpenOrdersCard({
    Key? key,
    required this.orders,
    required this.isLoading,
    required this.onRefresh,
    required this.onCancelOrder,
  }) : super(key: key);

  @override
  Widget build(BuildContext context) {
    return HudCard(
      borderColor: const Color(0xFF00D4FF),
      glowColor: const Color(0x2200D4FF),
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            // Заголовок
            Row(
              mainAxisAlignment: MainAxisAlignment.spaceBetween,
              children: [
                Row(
                  children: [
                    const Icon(Icons.list_alt_rounded, color: Color(0xFF00D4FF), size: 18),
                    const SizedBox(width: 8),
                    Text(
                      'АКТИВНЫЕ ОРДЕРА (${orders.length})',
                      style: GoogleFonts.orbitron(
                        color: const Color(0xFF00D4FF),
                        fontSize: 12,
                        fontWeight: FontWeight.bold,
                        letterSpacing: 1.1,
                      ),
                    ),
                  ],
                ),
                IconButton(
                  icon: const Icon(Icons.refresh, color: Color(0xFF00D4FF), size: 18),
                  onPressed: onRefresh,
                  tooltip: 'Обновить ордера',
                  padding: EdgeInsets.zero,
                  constraints: const BoxConstraints(),
                ),
              ],
            ),
            const SizedBox(height: 12),

            if (isLoading)
              const Padding(
                padding: EdgeInsets.symmetric(vertical: 24),
                child: Center(
                  child: CircularProgressIndicator(strokeWidth: 2, color: Color(0xFF00D4FF)),
                ),
              )
            else if (orders.isEmpty)
              Container(
                width: double.infinity,
                padding: const EdgeInsets.symmetric(vertical: 20),
                alignment: Alignment.Center,
                decoration: BoxDecoration(
                  color: const Color(0xFF071220),
                  borderRadius: BorderRadius.circular(6),
                ),
                child: Column(
                  children: [
                    const Icon(Icons.inbox_outlined, color: Color(0xFF4A6582), size: 28),
                    const SizedBox(height: 6),
                    Text(
                      'Нет активных ордеров',
                      style: GoogleFonts.rajdhani(
                        color: const Color(0xFF7E9BB8),
                        fontSize: 13,
                      ),
                    ),
                  ],
                ),
              )
            else
              ListView.separated(
                shrinkWrap: true,
                physics: const NeverScrollableScrollPhysics(),
                itemCount: orders.length,
                separatorBuilder: (_, __) => const Divider(color: Color(0x1A00D4FF), height: 12),
                itemBuilder: (ctx, i) {
                  final order = orders[i];
                  final isBuy = order.isBuy;
                  final sideColor = isBuy ? const Color(0xFF00E676) : const Color(0xFFFF5252);
                  final timeStr = DateFormat('HH:mm:ss').format(
                    DateTime.fromMillisecondsSinceEpoch(order.time),
                  );

                  return Row(
                    mainAxisAlignment: MainAxisAlignment.spaceBetween,
                    children: [
                      // Сторона + Символ
                      Row(
                        children: [
                          Container(
                            padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 2),
                            decoration: BoxDecoration(
                              color: sideColor.withOpacity(0.2),
                              borderRadius: BorderRadius.circular(4),
                              border: Border.all(color: sideColor, width: 0.8),
                            ),
                            child: Text(
                              order.side,
                              style: GoogleFonts.orbitron(
                                color: sideColor,
                                fontSize: 10,
                                fontWeight: FontWeight.bold,
                              ),
                            ),
                          ),
                          const SizedBox(width: 8),
                          Column(
                            crossAxisAlignment: CrossAxisAlignment.start,
                            children: [
                              Text(
                                order.symbol,
                                style: GoogleFonts.orbitron(
                                  color: Colors.white,
                                  fontSize: 12,
                                  fontWeight: FontWeight.bold,
                                ),
                              ),
                              Text(
                                timeStr,
                                style: GoogleFonts.rajdhani(
                                  color: const Color(0xFF7E9BB8),
                                  fontSize: 10,
                                ),
                              ),
                            ],
                          ),
                        ],
                      ),

                      // Цена и объем
                      Column(
                        crossAxisAlignment: CrossAxisAlignment.end,
                        children: [
                          Text(
                            '${order.price.toStringAsFixed(2)} USDT',
                            style: GoogleFonts.rajdhani(
                              color: const Color(0xFFFF8A65), // Персиковый акцент
                              fontSize: 13,
                              fontWeight: FontWeight.bold,
                            ),
                          ),
                          Text(
                            'Qty: ${order.origQty}',
                            style: GoogleFonts.rajdhani(
                              color: const Color(0xFFB0C4DE),
                              fontSize: 11,
                            ),
                          ),
                        ],
                      ),

                      // Кнопка отмены
                      IconButton(
                        icon: const Icon(Icons.cancel_outlined, color: Color(0xFFFF5252), size: 20),
                        tooltip: 'Отменить ордер',
                        onPressed: () => onCancelOrder(order),
                      ),
                    ],
                  );
                },
              ),
          ],
        ),
      ),
    );
  }
}
