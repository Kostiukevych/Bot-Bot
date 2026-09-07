import 'package:flutter/material.dart';
import 'package:google_fonts/google_fonts.dart';
import '../models/market_models.dart';
import '../screens/api_settings_screen.dart';

/// КАРТОЧКА РАЗМЕРА ПОЗИЦИИ (БЕЗ ХАРДКОДА ДАННЫХ)
/// Показывает:
/// - Слайдер выбора процента от реального доступного баланса Free USDT
/// - Поле ввода суммы в USDT с валидацией LOT_SIZE и MIN_NOTIONAL
/// - Автоматический пересчёт количества базового актива по реальной цене
class PositionSizeCard extends StatefulWidget {
  final double? freeUsdt;
  final double? currentPrice;
  final TradingPair? selectedPair;
  final Function(double usdtAmount, double baseAssetQty) onPositionChanged;

  const PositionSizeCard({
    Key? key,
    required this.freeUsdt,
    required this.currentPrice,
    required this.selectedPair,
    required this.onPositionChanged,
  }) : super(key: key);

  @override
  State<PositionSizeCard> createState() => _PositionSizeCardState();
}

class _PositionSizeCardState extends State<PositionSizeCard> {
  final TextEditingController _amountController = TextEditingController();
  double _sliderPercent = 25.0;

  @override
  void initState() {
    super.initState();
    if (widget.freeUsdt != null && widget.freeUsdt! > 0) {
      _applyPercent(25.0);
    }
  }

  @override
  void didUpdateWidget(covariant PositionSizeCard oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.currentPrice != widget.currentPrice ||
        oldWidget.freeUsdt != widget.freeUsdt) {
      // Если контроллер пуст или было изменение баланса
      if (_amountController.text.isEmpty && widget.freeUsdt != null && widget.freeUsdt! > 0) {
        _applyPercent(_sliderPercent);
      } else {
        _recalculate();
      }
    }
  }

  void _applyPercent(double percent) {
    final free = widget.freeUsdt ?? 0.0;
    setState(() {
      _sliderPercent = percent;
      final calculated = (free * (percent / 100)).clamp(0.0, free);
      _amountController.text = calculated > 0 ? calculated.toStringAsFixed(2) : '';
    });
    _recalculate();
  }

  void _recalculate() {
    final amount = double.tryParse(_amountController.text.trim()) ?? 0.0;
    final price = widget.currentPrice ?? 0.0;
    final qty = price > 0 ? (amount / price) : 0.0;
    widget.onPositionChanged(amount, qty);
  }

  @override
  Widget build(BuildContext context) {
    final pair = widget.selectedPair;
    final free = widget.freeUsdt ?? 0.0;
    final price = widget.currentPrice ?? 0.0;
    final enteredAmount = double.tryParse(_amountController.text.trim()) ?? 0.0;
    final baseQty = price > 0 ? (enteredAmount / price) : 0.0;

    // Валидация правил биржи
    final minNotional = pair?.minNotional ?? 10.0;
    final minLot = pair?.minQty ?? 0.0001;

    final isBelowMinNotional = enteredAmount > 0 && enteredAmount < minNotional;
    final isBelowMinLot = baseQty > 0 && baseQty < minLot;
    final isExceedsBalance = enteredAmount > free;

    return HudCard(
      borderColor: (isBelowMinNotional || isExceedsBalance)
          ? const Color(0xFFFF5252)
          : const Color(0xFF00D4FF),
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
                    const Icon(Icons.tune_outlined, color: Color(0xFF00D4FF), size: 18),
                    const SizedBox(width: 8),
                    Text(
                      'РАЗМЕР ПОЗИЦИИ',
                      style: GoogleFonts.orbitron(
                        color: const Color(0xFF00D4FF),
                        fontSize: 12,
                        fontWeight: FontWeight.bold,
                        letterSpacing: 1.1,
                      ),
                    ),
                  ],
                ),
                Text(
                  widget.freeUsdt != null
                      ? 'Доступно: ${free.toStringAsFixed(2)} USDT'
                      : 'Доступно: ---',
                  style: GoogleFonts.rajdhani(
                    color: const Color(0xFF00E676),
                    fontWeight: FontWeight.bold,
                    fontSize: 12,
                  ),
                ),
              ],
            ),
            const SizedBox(height: 12),

            // Поле ввода суммы и пересчет количества
            Row(
              children: [
                Expanded(
                  child: TextFormField(
                    controller: _amountController,
                    keyboardType: const TextInputType.numberWithOptions(decimal: true),
                    style: GoogleFonts.rajdhani(
                      color: const Color(0xFFFF8A65),
                      fontSize: 20,
                      fontWeight: FontWeight.bold,
                    ),
                    onChanged: (val) {
                      final parsed = double.tryParse(val.trim()) ?? 0.0;
                      if (free > 0) {
                        setState(() {
                          _sliderPercent = (parsed / free * 100).clamp(0.0, 100.0);
                        });
                      }
                      _recalculate();
                    },
                    decoration: InputDecoration(
                      labelText: 'СУММА В USDT',
                      labelStyle: GoogleFonts.orbitron(color: const Color(0xFF7E9BB8), fontSize: 11),
                      hintText: free > 0 ? '0.00' : 'Нет средств',
                      hintStyle: GoogleFonts.rajdhani(color: const Color(0xFF4A6582)),
                      filled: true,
                      fillColor: const Color(0xFF071220),
                      prefixIcon: const Icon(Icons.attach_money, color: Color(0xFF00D4FF), size: 18),
                      contentPadding: const EdgeInsets.symmetric(horizontal: 12, vertical: 10),
                      border: OutlineInputBorder(
                        borderRadius: BorderRadius.circular(6),
                        borderSide: const BorderSide(color: Color(0x4400D4FF)),
                      ),
                      focusedBorder: OutlineInputBorder(
                        borderRadius: BorderRadius.circular(6),
                        borderSide: const BorderSide(color: Color(0xFF00D4FF), width: 1.5),
                      ),
                    ),
                  ),
                ),
                const SizedBox(width: 12),
                // Автоматический пересчёт в базовый актив
                Expanded(
                  child: Container(
                    padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 8),
                    decoration: BoxDecoration(
                      color: const Color(0x1A00D4FF),
                      borderRadius: BorderRadius.circular(6),
                      border: Border.all(color: const Color(0x3300D4FF)),
                    ),
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(
                          'ИТОГО (${pair?.baseAsset ?? "QTY"})',
                          style: GoogleFonts.orbitron(
                            color: const Color(0xFF7E9BB8),
                            fontSize: 9,
                            letterSpacing: 0.8,
                          ),
                        ),
                        const SizedBox(height: 2),
                        Text(
                          price > 0 && baseQty > 0
                              ? baseQty.toStringAsFixed(baseQty < 1 ? 6 : 4)
                              : '0.00',
                          style: GoogleFonts.rajdhani(
                            color: Colors.white,
                            fontSize: 16,
                            fontWeight: FontWeight.bold,
                          ),
                        ),
                      ],
                    ),
                  ),
                ),
              ],
            ),
            const SizedBox(height: 12),

            // Слайдер выбора процента
            Row(
              mainAxisAlignment: MainAxisAlignment.spaceBetween,
              children: [
                Text(
                  'ДОЛЯ ОТ БАЛАНСА',
                  style: GoogleFonts.orbitron(color: const Color(0xFF7E9BB8), fontSize: 10),
                ),
                Text(
                  '${_sliderPercent.toStringAsFixed(0)}%',
                  style: GoogleFonts.rajdhani(
                    color: const Color(0xFF00D4FF),
                    fontSize: 14,
                    fontWeight: FontWeight.bold,
                  ),
                ),
              ],
            ),
            SliderTheme(
              data: SliderThemeData(
                activeTrackColor: const Color(0xFF00D4FF),
                inactiveTrackColor: const Color(0xFF071220),
                thumbColor: const Color(0xFF00D4FF),
                overlayColor: const Color(0x2200D4FF),
                trackHeight: 3,
                thumbShape: const RoundSliderThumbShape(enabledThumbRadius: 7),
              ),
              child: Slider(
                value: _sliderPercent,
                min: 0,
                max: 100,
                divisions: 100,
                onChanged: free > 0
                    ? (val) {
                        _applyPercent(val);
                      }
                    : null,
              ),
            ),

            // Кнопки-пресеты: 10%, 25%, 50%, 75%, 100%
            Row(
              mainAxisAlignment: MainAxisAlignment.spaceBetween,
              children: [10, 25, 50, 75, 100].map((pct) {
                final isSel = (_sliderPercent - pct).abs() < 1.0;
                return Expanded(
                  child: Padding(
                    padding: const EdgeInsets.symmetric(horizontal: 2),
                    child: OutlinedButton(
                      style: OutlinedButton.styleFrom(
                        padding: const EdgeInsets.symmetric(vertical: 4),
                        backgroundColor: isSel ? const Color(0x3300D4FF) : const Color(0xFF071220),
                        side: BorderSide(
                          color: isSel ? const Color(0xFF00D4FF) : const Color(0x3300D4FF),
                        ),
                        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(4)),
                      ),
                      onPressed: free > 0 ? () => _applyPercent(pct.toDouble()) : null,
                      child: Text(
                        '$pct%',
                        style: GoogleFonts.orbitron(
                          color: isSel ? const Color(0xFF00D4FF) : const Color(0xFF7E9BB8),
                          fontSize: 10,
                          fontWeight: FontWeight.bold,
                        ),
                      ),
                    ),
                  ),
                );
              }).toList(),
            ),

            // Предупреждения валидации
            if (isExceedsBalance) ...[
              const SizedBox(height: 8),
              Row(
                children: [
                  const Icon(Icons.warning, color: Color(0xFFFF5252), size: 14),
                  const SizedBox(width: 4),
                  Text(
                    'Сумма превышает доступный баланс (${free.toStringAsFixed(2)} USDT)',
                    style: GoogleFonts.rajdhani(color: const Color(0xFFFF5252), fontSize: 11),
                  ),
                ],
              ),
            ] else if (isBelowMinNotional) ...[
              const SizedBox(height: 8),
              Row(
                children: [
                  const Icon(Icons.warning, color: Color(0xFFFFB300), size: 14),
                  const SizedBox(width: 4),
                  Text(
                    'Сумма ниже MIN_NOTIONAL биржи ($minNotional USDT)',
                    style: GoogleFonts.rajdhani(color: const Color(0xFFFFB300), fontSize: 11),
                  ),
                ],
              ),
            ] else if (isBelowMinLot) ...[
              const SizedBox(height: 8),
              Row(
                children: [
                  const Icon(Icons.warning, color: Color(0xFFFFB300), size: 14),
                  const SizedBox(width: 4),
                  Text(
                    'Количество меньше минимального лота ($minLot ${pair?.baseAsset ?? ""})',
                    style: GoogleFonts.rajdhani(color: const Color(0xFFFFB300), fontSize: 11),
                  ),
                ],
              ),
            ],
          ],
        ),
      ),
    );
  }
}
