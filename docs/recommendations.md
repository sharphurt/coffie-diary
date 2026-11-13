# Рекомендации

Алгоритм раздела 13 ТЗ **пока не реализован**. Endpoint `/api/v1/recommendations` возвращает пустые items и объяснение отсутствия каталога. Score, прогнозы, проценты, отзывы и подборки не подставляются.

Следующая реализация должна включать: группировку опор по продукту/ревизии с равным весом пачек, uncertainty weights, similarity/coverage, до пяти опор, shrinkage prior, прогноз шести сенсорных шкал, taste_fit, холодный старт, строгие фильтры и свежесть, provenance, неизменяемые RecommendationRun и producer-only baseline.

Детерминированные datasets и сравнение baseline пока отсутствуют. ConsumptionEvent должен оставаться вне taste score.
