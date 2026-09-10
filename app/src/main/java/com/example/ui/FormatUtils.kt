package com.example.ui

import java.util.Locale

/**
 * Единая утилита форматирования цен для графиков и панелей.
 */
fun formatPrice(price: Double): String {
  return when {
    price >= 1000.0 -> "%.2f".format(Locale.US, price)
    price >= 1.0 -> "%.2f".format(Locale.US, price)
    price >= 0.0001 -> "%.4f".format(Locale.US, price)
    else -> "%.6f".format(Locale.US, price)
  }
}
