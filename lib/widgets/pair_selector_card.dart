import 'package:flutter/material.dart';
import 'package:google_fonts/google_fonts.dart';
import '../models/market_models.dart';
import '../screens/api_settings_screen.dart';

/// КАРТОЧКА ВЫБОРА ПАРЫ (БЕЗ ХАРДКОДА ДАННЫХ)
/// Показывает:
/// - Загрузку пар из GET /api/v3/exchangeInfo (индикатор/спиннер)
/// - Ошибку при сбое загрузки списка пар
/// - Реальный WebSocket/REST тикер (цена, изменение за 24ч) без дефолтных чисел
class PairSelectorCard extends StatefulWidget {
  final List<TradingPair> pairs;
  final TradingPair? selectedPair;
  final TickerData? tickerData;
  final bool isLoading;
  final String? errorMessage;
  final VoidCallback onRefresh;
  final Function(TradingPair) onPairSelected;

  const PairSelectorCard({
    Key? key,
    required this.pairs,
    required this.selectedPair,
    required this.tickerData,
    required this.isLoading,
    this.errorMessage,
    required this.onRefresh,
    required this.onPairSelected,
  }) : super(key: key);

  @override
  State<PairSelectorCard> createState() => _PairSelectorCardState();
}

class _PairSelectorCardState extends State<PairSelectorCard> {
  void _openPairSearchModal() {
    if (widget.pairs.isEmpty) return;
    showModalBottomSheet(
      context: context,
      backgroundColor: Colors.transparent,
      isScrollControlled: true,
      builder: (ctx) => _PairSearchModal(
        pairs: widget.pairs,
        selectedPair: widget.selectedPair,
        onSelected: (pair) {
          Navigator.pop(ctx);
          widget.onPairSelected(pair);
        },
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final ticker = widget.tickerData;
    final isPos = ticker?.isPositive ?? true;
    final changeColor = isPos ? const Color(0xFF00E676) : const Color(0xFFFF5252);

    return HudCard(
      borderColor: widget.errorMessage != null ? const Color(0xFFFF5252) : const Color(0xFF00D4FF),
      glowColor: widget.errorMessage != null ? const Color(0x22FF5252) : const Color(0x2200D4FF),
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
                    const Icon(Icons.candlestick_chart_outlined, color: Color(0xFF00D4FF), size: 18),
                    const SizedBox(width: 8),
                    Text(
                      'ТОРГОВАЯ ПАРА (SPOT TESTNET)',
                      style: GoogleFonts.orbitron(
                        color: const Color(0xFF00D4FF),
                        fontSize: 12,
                        fontWeight: FontWeight.bold,
                        letterSpacing: 1.1,
                      ),
                    ),
                  ],
                ),
                Container(
                  padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 2),
                  decoration: BoxDecoration(
                    color: const Color(0x2600D4FF),
                    borderRadius: BorderRadius.circular(4),
                    border: Border.all(color: const Color(0xFF00D4FF), width: 0.8),
                  ),
                  child: Row(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      Container(
                        width: 6,
                        height: 6,
                        decoration: BoxDecoration(
                          shape: BoxShape.circle,
                          color: ticker != null ? const Color(0xFF00E676) : const Color(0xFFFF8A65),
                        ),
                      ),
                      const SizedBox(width: 4),
                      Text(
                        ticker != null ? 'LIVE WS' : 'CONNECTING',
                        style: GoogleFonts.orbitron(
                          color: const Color(0xFF00D4FF),
                          fontSize: 9,
                          fontWeight: FontWeight.bold,
                        ),
                      ),
                    ],
                  ),
                ),
              ],
            ),
            const SizedBox(height: 12),

            // Ошибка загрузки списка пар
            if (widget.errorMessage != null && widget.pairs.isEmpty) ...[
              Container(
                width: double.infinity,
                padding: const EdgeInsets.all(12),
                decoration: BoxDecoration(
                  color: const Color(0x1AFF1744),
                  borderRadius: BorderRadius.circular(6),
                  border: Border.all(color: const Color(0x66FF1744)),
                ),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      'Ошибка exchangeInfo: ${widget.errorMessage}',
                      style: GoogleFonts.rajdhani(color: const Color(0xFFFF8A80), fontSize: 12),
                    ),
                    const SizedBox(height: 6),
                    HudButton(
                      text: 'ПОВТОРИТЬ ЗАГРУЗКУ ПАР',
                      icon: Icons.refresh,
                      color: const Color(0xFFFF5252),
                      isSmall: true,
                      isOutlined: true,
                      onPressed: widget.onRefresh,
                    ),
                  ],
                ),
              ),
            ] else ...[
              // Кнопка выбора пары
              InkWell(
                onTap: widget.isLoading && widget.pairs.isEmpty ? null : _openPairSearchModal,
                borderRadius: BorderRadius.circular(6),
                child: Container(
                  padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 10),
                  decoration: BoxDecoration(
                    color: const Color(0xFF071220),
                    borderRadius: BorderRadius.circular(6),
                    border: Border.all(color: const Color(0x6600D4FF), width: 1.2),
                  ),
                  child: Row(
                    children: [
                      const Icon(Icons.search, color: Color(0xFF00D4FF), size: 18),
                      const SizedBox(width: 10),
                      Expanded(
                        child: Text(
                          widget.pairs.isEmpty && widget.isLoading
                              ? 'Загрузка пар с Binance...'
                              : (widget.selectedPair?.symbol ?? 'Выберите пару...'),
                          style: GoogleFonts.orbitron(
                            color: Colors.white,
                            fontSize: 14,
                            fontWeight: FontWeight.bold,
                            letterSpacing: 1.1,
                          ),
                        ),
                      ),
                      if (widget.isLoading && widget.pairs.isEmpty)
                        const SizedBox(
                          width: 16,
                          height: 16,
                          child: CircularProgressIndicator(strokeWidth: 2, color: Color(0xFF00D4FF)),
                        )
                      else ...[
                        Text(
                          '${widget.pairs.length} пар',
                          style: GoogleFonts.rajdhani(color: const Color(0xFF7E9BB8), fontSize: 11),
                        ),
                        const SizedBox(width: 4),
                        const Icon(Icons.arrow_drop_down, color: Color(0xFF00D4FF), size: 24),
                      ],
                    ],
                  ),
                ),
              ),
              const SizedBox(height: 14),

              // Отображение цены и изменения 24ч (без заглушек)
              Row(
                mainAxisAlignment: MainAxisAlignment.spaceBetween,
                crossAxisAlignment: CrossAxisAlignment.center,
                children: [
                  Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        'ТЕКУЩАЯ ЦЕНА',
                        style: GoogleFonts.orbitron(
                          color: const Color(0xFF7E9BB8),
                          fontSize: 10,
                          letterSpacing: 0.8,
                        ),
                      ),
                      const SizedBox(height: 2),
                      if (ticker != null)
                        Text(
                          ticker.lastPrice.toStringAsFixed(ticker.lastPrice < 1 ? 4 : 2),
                          style: GoogleFonts.rajdhani(
                            color: Colors.white,
                            fontSize: 26,
                            fontWeight: FontWeight.bold,
                            letterSpacing: 1.1,
                          ),
                        )
                      else if (widget.selectedPair != null)
                        Row(
                          children: [
                            const SizedBox(
                              width: 16,
                              height: 16,
                              child: CircularProgressIndicator(strokeWidth: 2, color: Color(0xFF00D4FF)),
                            ),
                            const SizedBox(width: 8),
                            Text(
                              'Получение тикера...',
                              style: GoogleFonts.rajdhani(color: const Color(0xFF7E9BB8), fontSize: 14),
                            ),
                          ],
                        )
                      else
                        Text(
                          '---',
                          style: GoogleFonts.rajdhani(
                            color: const Color(0xFF7E9BB8),
                            fontSize: 26,
                            fontWeight: FontWeight.bold,
                          ),
                        ),
                    ],
                  ),
                  if (ticker != null)
                    Container(
                      padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 6),
                      decoration: BoxDecoration(
                        color: changeColor.withOpacity(0.15),
                        borderRadius: BorderRadius.circular(6),
                        border: Border.all(color: changeColor, width: 1.2),
                      ),
                      child: Row(
                        children: [
                          Icon(
                            isPos ? Icons.arrow_drop_up : Icons.arrow_drop_down,
                            color: changeColor,
                            size: 20,
                          ),
                          Text(
                            '${isPos ? "+" : ""}${ticker.priceChangePercent.toStringAsFixed(2)}%',
                            style: GoogleFonts.rajdhani(
                              color: changeColor,
                              fontSize: 16,
                              fontWeight: FontWeight.bold,
                            ),
                          ),
                        ],
                      ),
                    )
                  else
                    Container(
                      padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 4),
                      decoration: BoxDecoration(
                        color: const Color(0xFF071220),
                        borderRadius: BorderRadius.circular(6),
                        border: Border.all(color: const Color(0x3300D4FF)),
                      ),
                      child: Text(
                        '24H: ---',
                        style: GoogleFonts.orbitron(color: const Color(0xFF7E9BB8), fontSize: 10),
                      ),
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

/// Модальный поиск и фильтр пар
class _PairSearchModal extends StatefulWidget {
  final List<TradingPair> pairs;
  final TradingPair? selectedPair;
  final Function(TradingPair) onSelected;

  const _PairSearchModal({
    required this.pairs,
    required this.selectedPair,
    required this.onSelected,
  });

  @override
  State<_PairSearchModal> createState() => _PairSearchModalState();
}

class _PairSearchModalState extends State<_PairSearchModal> {
  final TextEditingController _searchController = TextEditingController();
  List<TradingPair> _filtered = [];

  @override
  void initState() {
    super.initState();
    _filtered = widget.pairs;
  }

  void _onSearch(String query) {
    setState(() {
      if (query.trim().isEmpty) {
        _filtered = widget.pairs;
      } else {
        _filtered = widget.pairs
            .where((p) => p.symbol.toLowerCase().contains(query.toLowerCase().trim()))
            .toList();
      }
    });
  }

  @override
  Widget build(BuildContext context) {
    return Container(
      height: MediaQuery.of(context).size.height * 0.75,
      decoration: const BoxDecoration(
        color: Color(0xFF0A1628),
        borderRadius: BorderRadius.vertical(top: Radius.circular(16)),
        border: Border(top: BorderSide(color: Color(0xFF00D4FF), width: 2)),
      ),
      child: Column(
        children: [
          // Заголовок модалки
          Padding(
            padding: const EdgeInsets.fromLTRB(16, 16, 16, 8),
            child: Row(
              mainAxisAlignment: MainAxisAlignment.spaceBetween,
              children: [
                Text(
                  'ВЫБОР ТОРГОВОЙ ПАРЫ (BINANCE TESTNET)',
                  style: GoogleFonts.orbitron(
                    color: const Color(0xFF00D4FF),
                    fontSize: 12,
                    fontWeight: FontWeight.bold,
                  ),
                ),
                IconButton(
                  icon: const Icon(Icons.close, color: Color(0xFF7E9BB8)),
                  onPressed: () => Navigator.pop(context),
                ),
              ],
            ),
          ),

          // Поле поиска
          Padding(
            padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
            child: TextField(
              controller: _searchController,
              onChanged: _onSearch,
              style: GoogleFonts.rajdhani(color: Colors.white, fontSize: 16),
              decoration: InputDecoration(
                hintText: 'Поиск пары (напр. BTC, ETH, SOL)...',
                hintStyle: GoogleFonts.rajdhani(color: const Color(0xFF7E9BB8)),
                prefixIcon: const Icon(Icons.search, color: Color(0xFF00D4FF)),
                filled: true,
                fillColor: const Color(0xFF071220),
                contentPadding: const EdgeInsets.symmetric(horizontal: 14, vertical: 10),
                border: OutlineInputBorder(
                  borderRadius: BorderRadius.circular(8),
                  borderSide: const BorderSide(color: Color(0x3300D4FF)),
                ),
                focusedBorder: OutlineInputBorder(
                  borderRadius: BorderRadius.circular(8),
                  borderSide: const BorderSide(color: Color(0xFF00D4FF), width: 1.5),
                ),
              ),
            ),
          ),

          // Список найденных пар
          Expanded(
            child: _filtered.isEmpty
                ? Center(
                    child: Text(
                      'Пары не найдены',
                      style: GoogleFonts.rajdhani(color: const Color(0xFF7E9BB8), fontSize: 14),
                    ),
                  )
                : ListView.separated(
                    padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
                    itemCount: _filtered.length,
                    separatorBuilder: (_, __) => const Divider(color: Color(0x1A00D4FF), height: 1),
                    itemBuilder: (ctx, idx) {
                      final p = _filtered[idx];
                      final isSel = widget.selectedPair?.symbol == p.symbol;
                      return ListTile(
                        dense: true,
                        contentPadding: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
                        tileColor: isSel ? const Color(0x2200D4FF) : null,
                        title: Text(
                          p.symbol,
                          style: GoogleFonts.orbitron(
                            color: isSel ? const Color(0xFF00D4FF) : Colors.white,
                            fontSize: 13,
                            fontWeight: FontWeight.bold,
                          ),
                        ),
                        subtitle: Text(
                          'Базовый: ${p.baseAsset} | Min Lot: ${p.minQty} | Min Notional: ${p.minNotional} USDT',
                          style: GoogleFonts.rajdhani(color: const Color(0xFF7E9BB8), fontSize: 11),
                        ),
                        trailing: isSel
                            ? const Icon(Icons.check, color: Color(0xFF00D4FF), size: 18)
                            : null,
                        onTap: () => widget.onSelected(p),
                      );
                    },
                  ),
          ),
        ],
      ),
    );
  }
}
