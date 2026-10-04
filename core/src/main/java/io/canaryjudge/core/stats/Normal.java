package io.canaryjudge.core.stats;

/**
 * Standard normal distribution: density, CDF, upper tail and quantile.
 *
 * <p>The CDF uses the Taylor series for |x| < 3 and a continued fraction for the tail, so upper-tail
 * probabilities keep their relative precision far out (p-values of 1e-12 are not computed as 1 minus
 * something close to 1). The quantile starts from Acklam's rational approximation (relative error about
 * 1e-9) and polishes it with one Halley step against the accurate CDF. Both are checked against scipy
 * golden values in the tests.
 */
public final class Normal {
    private static final double LOG_SQRT_2PI = 0.91893853320467274178;

    private Normal() {}

    public static double pdf(double x) {
        return Math.exp(-0.5 * x * x - LOG_SQRT_2PI);
    }

    /** P(Z <= x). */
    public static double cdf(double x) {
        if (Double.isNaN(x)) return Double.NaN;
        return x >= 0 ? 1.0 - upperTail(x) : upperTail(-x);
    }

    /** P(Z > x), accurate in relative terms for large x. */
    public static double upperTail(double x) {
        if (Double.isNaN(x)) return Double.NaN;
        if (x < 0) return 1.0 - upperTail(-x);
        if (x < 3.0) {
            // Marsaglia's series: Phi(x) = 1/2 + phi(x) * (x + x^3/3 + x^5/(3*5) + ...)
            double s = x, t = 0, b = x, q = x * x;
            int i = 1;
            while (s != t) {
                t = s;
                i += 2;
                b *= q / i;
                s = t + b;
            }
            return 0.5 - s * pdf(x);
        }
        if (x > 40) return 0.0;
        // Continued fraction for Mills' ratio, evaluated with the modified Lentz method.
        double tiny = 1e-300;
        double f = x, c = x, d = 0;
        for (int k = 1; k < 500; k++) {
            double a = k;
            d = x + a * d;
            if (Math.abs(d) < tiny) d = tiny;
            c = x + a / c;
            if (Math.abs(c) < tiny) c = tiny;
            d = 1.0 / d;
            double delta = c * d;
            f *= delta;
            if (Math.abs(delta - 1.0) < 1e-16) break;
        }
        return pdf(x) / f;
    }

    private static final double[] A = {-3.969683028665376e+01, 2.209460984245205e+02, -2.759285104469687e+02,
            1.383577518672690e+02, -3.066479806614716e+01, 2.506628277459239e+00};
    private static final double[] B = {-5.447609879822406e+01, 1.615858368580409e+02, -1.556989798598866e+02,
            6.680131188771972e+01, -1.328068155288572e+01};
    private static final double[] C = {-7.784894002430293e-03, -3.223964580411365e-01, -2.400758277161838e+00,
            -2.549732539343734e+00, 4.374664141464968e+00, 2.938163982698783e+00};
    private static final double[] D = {7.784695709041462e-03, 3.224671290700398e-01, 2.445134137142996e+00,
            3.754408661907416e+00};

    /** The p-quantile of the standard normal, p in (0, 1). */
    public static double quantile(double p) {
        if (Double.isNaN(p) || p < 0 || p > 1) return Double.NaN;
        if (p == 0) return Double.NEGATIVE_INFINITY;
        if (p == 1) return Double.POSITIVE_INFINITY;
        double plow = 0.02425, x;
        if (p < plow) {
            double q = Math.sqrt(-2 * Math.log(p));
            x = (((((C[0] * q + C[1]) * q + C[2]) * q + C[3]) * q + C[4]) * q + C[5])
                    / ((((D[0] * q + D[1]) * q + D[2]) * q + D[3]) * q + 1);
        } else if (p <= 1 - plow) {
            double q = p - 0.5, r = q * q;
            x = (((((A[0] * r + A[1]) * r + A[2]) * r + A[3]) * r + A[4]) * r + A[5]) * q
                    / (((((B[0] * r + B[1]) * r + B[2]) * r + B[3]) * r + B[4]) * r + 1);
        } else {
            double q = Math.sqrt(-2 * Math.log1p(-p));
            x = -(((((C[0] * q + C[1]) * q + C[2]) * q + C[3]) * q + C[4]) * q + C[5])
                    / ((((D[0] * q + D[1]) * q + D[2]) * q + D[3]) * q + 1);
        }
        // One Halley step against the accurate CDF; work in the smaller tail to keep precision.
        double e = p < 0.5 ? (upperTail(-x) - p) : -((upperTail(x)) - (1 - p));
        double u = e * Math.sqrt(2 * Math.PI) * Math.exp(x * x / 2);
        return x - u / (1 + x * u / 2);
    }
}
