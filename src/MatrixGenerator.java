import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Random;

/** Отдельный процесс ОС: генерирует матрицы и отправляет их родителю через pipe. */
public class MatrixGenerator {
    private static final int RANDOM_BOUND = 10;
    // Фиксированное seed делает входные данные одинаковыми при повторных замерах.
    private static final long SEED = 2026L;

    public static void main(String[] args) throws IOException {
        if (args.length == 0) {
            throw new IllegalArgumentException("Не указан режим генератора");
        }
        if (args[0].equals("normal")) {
            generateNormal(args);
        } else if (args[0].equals("triangular")) {
            generateTriangular(args);
        } else {
            throw new IllegalArgumentException("Неизвестный режим генератора");
        }
    }

    private static void generateNormal(String[] args) throws IOException {
        int n = Integer.parseInt(args[1]);
        int m = Integer.parseInt(args[2]);
        int k = Integer.parseInt(args[3]);
        Random random = new Random(SEED);

        long generationStart = System.nanoTime();
        long[][] a = randomMatrix(n, m, random);
        long[][] b = randomMatrix(m, k, random);
        double generationMs = elapsedMs(generationStart);

        DataOutputStream out = new DataOutputStream(new BufferedOutputStream(System.out));
        // Заголовок сообщает родителю размеры и время генерации.
        out.writeInt(n);
        out.writeInt(m);
        out.writeInt(k);
        out.writeDouble(generationMs);
        out.flush();

        long writeStart = System.nanoTime();
        writeMatrix(out, a);
        writeMatrix(out, b);
        out.flush();
        double pipeWriteMs = elapsedMs(writeStart);
        out.writeDouble(pipeWriteMs);
        out.flush();
    }

    private static void generateTriangular(String[] args) throws IOException {
        int n = Integer.parseInt(args[1]);
        int k = Integer.parseInt(args[2]);
        Random random = new Random(SEED);

        long generationStart = System.nanoTime();
        long[][] a = new long[n][];
        for (int i = 0; i < n; i++) {
            a[i] = new long[i + 1];
            for (int j = 0; j <= i; j++) {
                a[i][j] = random.nextInt(RANDOM_BOUND);
            }
        }
        long[][] b = randomMatrix(n, k, random);
        double generationMs = elapsedMs(generationStart);

        DataOutputStream out = new DataOutputStream(new BufferedOutputStream(System.out));
        out.writeInt(n);
        out.writeInt(k);
        out.writeDouble(generationMs);
        out.flush();

        long writeStart = System.nanoTime();
        writeMatrix(out, a); // Передаём только реально хранящиеся элементы треугольника.
        writeMatrix(out, b);
        out.flush();
        double pipeWriteMs = elapsedMs(writeStart);
        out.writeDouble(pipeWriteMs);
        out.flush();
    }

    private static long[][] randomMatrix(int rows, int cols, Random random) {
        long[][] matrix = new long[rows][cols];
        for (int i = 0; i < rows; i++) {
            for (int j = 0; j < cols; j++) {
                matrix[i][j] = random.nextInt(RANDOM_BOUND);
            }
        }
        return matrix;
    }

    private static void writeMatrix(DataOutputStream out, long[][] matrix) throws IOException {
        for (long[] row : matrix) {
            for (long value : row) {
                out.writeLong(value);
            }
        }
    }

    private static double elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000.0;
    }
}
