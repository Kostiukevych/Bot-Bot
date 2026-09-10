package com.example.service

import com.example.model.PairScanResult
import com.example.model.StrategyRiskConfig
import com.example.model.TradingPair
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

class MarketScannerService(private val strategyService: TradingStrategyService) {

  suspend fun scanTopPairs(
    marketService: BinanceMarketService,
    allPairs: List<TradingPair>,
    config: StrategyRiskConfig
  ): List<PairScanResult> = withContext(Dispatchers.IO) {
    val tickers = marketService.get24hrTickers()
    val allowedSymbols = allPairs.map { it.symbol }.toSet()
    val topTickers = tickers
      .filter { it.symbol in allowedSymbols }
      .sortedByDescending { it.quoteVolume }
      .take(config.scannerTopN)

    val semaphore = Semaphore(5) // ограничение параллельных запросов, чтобы не словить rate-limit Binance
    coroutineScope {
      val results = topTickers.map { ticker ->
        async {
          semaphore.withPermit {
            val pairInfo = allPairs.firstOrNull { it.symbol == ticker.symbol } ?: return@withPermit null
            val klines = strategyService.fetchKlines(ticker.symbol, config.interval, 60)
            if (klines.size < 21) return@withPermit null
            val signal = strategyService.evaluate(ticker.symbol, ticker.lastPrice, klines, config)
            PairScanResult(pairInfo, ticker, signal)
          }
        }
      }.awaitAll().filterNotNull()

      results.sortedByDescending { it.signal.score }
    }
  }
}
