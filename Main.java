import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Лабораторная № 2: многопоточное умножение матриц. */
public class Main {
    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            printHelp();
            return;
        }

        if (args[0].equals("normal")) {
            runNormal(args);
        } else if (args[0].equals("triangular")) {
            runTriangular(args);
        } else {
            printHelp();
        }
    }

    // Обычное умножение A(n x m) * B(m x k). Для оценки 3 и 4.
    private static void runNormal(String[] args) throws Exception {
        if (args.length != 5) {
            System.out.println("Использование: java Main normal n m k T");
            return;
        }

        int n = positiveInt(args[1], "n");
        int m = positiveInt(args[2], "m");
        int k = positiveInt(args[3], "k");
        int threadCount = positiveInt(args[4], "T");

        // Матрицы создаёт отдельный процесс и отправляет через pipe.
        long setupStart = System.nanoTime();
        Process process = startGenerator("normal", n, m, k);
        long[][] a;
        long[][] b;
        double generationMs;
        double pipeWriteMs;

        try (DataInputStream in = new DataInputStream(
                new BufferedInputStream(process.getInputStream()))) {
            checkSize(in.readInt(), n, "n");
            checkSize(in.readInt(), m, "m");
            checkSize(in.readInt(), k, "k");
            generationMs = in.readDouble();
            a = readMatrix(in, n, m);
            b = readMatrix(in, m, k);
            pipeWriteMs = in.readDouble();
        }
        checkGeneratorExit(process);
        double setupMs = elapsedMs(setupStart);

        long[][] c = new long[n][k];
        double multiplyMs = multiply(a, b, c, threadCount, false);

        System.out.println("Режим: обычное умножение матриц");
        System.out.printf("Размеры: A=%dx%d, B=%dx%d, C=%dx%d%n", n, m, m, k, n, k);
        System.out.printf("Рабочих потоков запрошено: %d%n", threadCount);
        System.out.printf("Генерация в отдельном процессе: %.3f мс%n", generationMs);
        System.out.printf("Запись матриц в pipe: %.3f мс%n", pipeWriteMs);
        System.out.printf("Полная подготовка данных (запуск + генерация + передача): %.3f мс%n", setupMs);
        System.out.printf("Время умножения: %.3f мс%n", multiplyMs);
        System.out.println("Контрольная сумма: " + checksum(c));
    }

    // A — нижнетреугольная матрица с хранимыми строками разной длины.
    private static void runTriangular(String[] args) throws Exception {
        if (args.length != 5) {
            System.out.println("Использование: java Main triangular n k T static|dynamic");
            return;
        }

        int n = positiveInt(args[1], "n");
        int k = positiveInt(args[2], "k");
        int threadCount = positiveInt(args[3], "T");
        String distribution = args[4].toLowerCase();
        if (!distribution.equals("static") && !distribution.equals("dynamic")) {
            throw new IllegalArgumentException("Укажите static или dynamic");
        }

        long setupStart = System.nanoTime();
        Process process = startGenerator("triangular", n, k);
        long[][] a = new long[n][];
        long[][] b;
        double generationMs;
        double pipeWriteMs;

        try (DataInputStream in = new DataInputStream(
                new BufferedInputStream(process.getInputStream()))) {
            checkSize(in.readInt(), n, "n");
            checkSize(in.readInt(), k, "k");
            generationMs = in.readDouble();
            for (int i = 0; i < n; i++) {
                a[i] = readRow(in, i + 1); // В строке i хранится i+1 элементов.
            }
            b = readMatrix(in, n, k);
            pipeWriteMs = in.readDouble();
        }
        checkGeneratorExit(process);
        double setupMs = elapsedMs(setupStart);

        long[][] c = new long[n][k];
        boolean dynamic = distribution.equals("dynamic");
        double multiplyMs = multiply(a, b, c, threadCount, dynamic);

        System.out.println("Режим: умножение с нижнетреугольной матрицей");
        System.out.printf("Размеры: A=%dx%d (хранятся только элементы треугольника), B=%dx%d, C=%dx%d%n",
                n, n, n, k, n, k);
        System.out.printf("Распределение строк: %s%n", dynamic ? "динамическое" : "статическое");
        System.out.printf("Рабочих потоков запрошено: %d%n", threadCount);
        System.out.printf("Генерация в отдельном процессе: %.3f мс%n", generationMs);
        System.out.printf("Запись матриц в pipe: %.3f мс%n", pipeWriteMs);
        System.out.printf("Полная подготовка данных (запуск + генерация + передача): %.3f мс%n", setupMs);
        System.out.printf("Время умножения: %.3f мс%n", multiplyMs);
        System.out.println("Контрольная сумма: " + checksum(c));
    }

    // Каждая рабочая нить считает целые строки результата.
    private static double multiply(long[][] a, long[][] b, long[][] c,
                                   int requestedThreads, boolean dynamic)
            throws InterruptedException {
        int n = a.length;
        int workers = Math.min(requestedThreads, n);
        AtomicInteger completedRows = new AtomicInteger(0);
        AtomicInteger nextRow = new AtomicInteger(0);
        AtomicBoolean showProgress = new AtomicBoolean(true);

        // Прогресс печатается отдельной нитью, чтобы не замедлять каждую строку вычислениями вывода.
        Thread progressThread = new Thread(() -> {
            int previousPercent = -1;
            while (showProgress.get()) {
                int percent = completedRows.get() * 100 / n;
                if (percent != previousPercent) {
                    System.out.print("\rПрогресс: " + percent + "%   ");
                    System.out.flush();
                    previousPercent = percent;
                }
                try {
                    Thread.sleep(200);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }, "progress");
        progressThread.start();

        Thread[] threads = new Thread[workers];
        long start = System.nanoTime();
        for (int id = 0; id < workers; id++) {
            final int threadId = id;
            threads[id] = new Thread(() -> {
                if (dynamic) {
                    // Динамически забираем очередной номер строки из общего счётчика.
                    int row;
                    while ((row = nextRow.getAndIncrement()) < n) {
                        calculateRow(a, b, c, row);
                        completedRows.incrementAndGet();
                    }
                } else {
                    // Статически заранее назначаем каждому потоку непрерывный блок строк.
                    int from = threadId * n / workers;
                    int to = (threadId + 1) * n / workers;
                    for (int row = from; row < to; row++) {
                        calculateRow(a, b, c, row);
                        completedRows.incrementAndGet();
                    }
                }
            }, "worker-" + id);
            threads[id].start();
        }

        // Главный поток ждёт завершения всех рабочих потоков.
        for (Thread thread : threads) {
            thread.join();
        }
        double multiplyMs = elapsedMs(start);

        showProgress.set(false);
        progressThread.join();
        System.out.println("\rПрогресс: 100%   ");
        return multiplyMs;
    }

    private static void calculateRow(long[][] a, long[][] b, long[][] c, int row) {
        for (int col = 0; col < b[0].length; col++) {
            long sum = 0;
            // Для обычной A длина строки одинакова; для треугольной равна row+1.
            for (int t = 0; t < a[row].length; t++) {
                sum += a[row][t] * b[t][col];
            }
            c[row][col] = sum;
        }
    }

    // Запускаем второй процесс Java, который создаёт матрицы и передаёт их в stdout через pipe.
    private static Process startGenerator(String mode, int... dimensions) throws IOException {
        String javaName = System.getProperty("os.name").toLowerCase().contains("win")
                ? "java.exe" : "java";
        String javaPath = System.getProperty("java.home") + File.separator + "bin"
                + File.separator + javaName;
        String classPath = System.getProperty("java.class.path");

        ProcessBuilder builder;
        if (mode.equals("normal")) {
            builder = new ProcessBuilder(javaPath, "-cp", classPath, "MatrixGenerator",
                    "normal", String.valueOf(dimensions[0]), String.valueOf(dimensions[1]),
                    String.valueOf(dimensions[2]));
        } else {
            builder = new ProcessBuilder(javaPath, "-cp", classPath, "MatrixGenerator",
                    "triangular", String.valueOf(dimensions[0]), String.valueOf(dimensions[1]));
        }
        builder.redirectError(ProcessBuilder.Redirect.INHERIT);
        return builder.start();
    }

    private static void checkGeneratorExit(Process process) throws Exception {
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new IOException("Процесс-генератор завершился с кодом " + exitCode);
        }
    }

    private static long[][] readMatrix(DataInputStream in, int rows, int cols) throws IOException {
        long[][] matrix = new long[rows][cols];
        for (int i = 0; i < rows; i++) {
            matrix[i] = readRow(in, cols);
        }
        return matrix;
    }

    private static long[] readRow(DataInputStream in, int length) throws IOException {
        long[] row = new long[length];
        for (int j = 0; j < length; j++) {
            row[j] = in.readLong();
        }
        return row;
    }

    private static long checksum(long[][] matrix) {
        long sum = 0;
        for (long[] row : matrix) {
            for (long value : row) {
                sum += value;
            }
        }
        return sum;
    }

    private static int positiveInt(String text, String name) {
        int value = Integer.parseInt(text);
        if (value < 1) {
            throw new IllegalArgumentException(name + " должно быть больше нуля");
        }
        return value;
    }

    private static void checkSize(int actual, int expected, String name) throws IOException {
        if (actual != expected) {
            throw new IOException("Размер " + name + " от генератора не совпадает с ожидаемым");
        }
    }

    private static double elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000.0;
    }

    private static void printHelp() {
        System.out.println("Лабораторная №2 — умножение матриц");
        System.out.println("Компиляция: javac Main.java MatrixGenerator.java");
        System.out.println("Обычная матрица: java Main normal n m k T");
        System.out.println("Треугольная A, статически: java Main triangular n k T static");
        System.out.println("Треугольная A, динамически: java Main triangular n k T dynamic");
        System.out.println("Пример: java Main normal 500 500 500 4");
    }
}
