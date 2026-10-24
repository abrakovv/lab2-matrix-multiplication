# Данные экспериментов

Все CSV разделены символом `;`.

- `raw/normal_2000_raw.csv`: столбцы Threads, Repeat, TimeMs, Checksum.
- `raw/triangular_2000_raw.csv`: столбцы Distribution, Threads, Repeat, TimeMs, Checksum.
- `summary/normal_2000_summary.csv`: число потоков, число использованных замеров, среднее время, ускорение, эффективность.
- `summary/triangular_2000_summary.csv`: те же показатели отдельно для static и dynamic.

Для среднего использованы три центральных значения из пяти исходных измерений каждой группы.
