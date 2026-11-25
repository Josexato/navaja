package com.josexato.navaja.survey.core;

import java.util.ArrayList;
import java.util.List;

/**
 * Una habitación: el contorno del piso marcado esquina por esquina, y la altura entre el piso y el techo. Todo en
 * metros, en el mismo plano que el recorrido. Las esquinas se guardan tal como se marcaron: no se cuadran ni se
 * corrigen, porque eso cambiaría las medidas sin que nadie lo pida.
 */
public final class Room {

    public final String name;
    /** Esquinas {x, y} en el orden en que se marcaron. */
    public final List<double[]> corners = new ArrayList<>();
    /** Altura del piso y del techo en el eje vertical; NaN mientras no se conozcan. */
    public double floorZ = Double.NaN, ceilingZ = Double.NaN;

    public Room(String name) {
        this.name = name;
    }

    public void corner(double x, double y) {
        corners.add(new double[]{x, y});
    }

    /** Con menos de tres esquinas no hay contorno. */
    public boolean closed() {
        return corners.size() >= 3;
    }

    /** Área del piso en m² (fórmula del cordón); 0 si no hay contorno. */
    public double area() {
        return Math.abs(signedArea());
    }

    double signedArea() {
        if (!closed()) return 0;
        double s = 0;
        for (int i = 0, n = corners.size(); i < n; i++) {
            double[] a = corners.get(i), b = corners.get((i + 1) % n);
            s += a[0] * b[1] - b[0] * a[1];
        }
        return s / 2;
    }

    /** Perímetro en metros, cerrando de la última esquina a la primera. */
    public double perimeter() {
        if (!closed()) return 0;
        double s = 0;
        for (int i = 0, n = corners.size(); i < n; i++) s += side(i);
        return s;
    }

    /** Largo de la pared que sale de la esquina i. */
    public double side(int i) {
        double[] a = corners.get(i), b = corners.get((i + 1) % corners.size());
        return Math.hypot(b[0] - a[0], b[1] - a[1]);
    }

    /** Altura del piso al techo en metros; NaN si falta alguno de los dos o el resultado no tiene sentido. */
    public double height() {
        double h = ceilingZ - floorZ;
        return h > 0 ? h : Double.NaN;
    }

    /** Volumen en m³; NaN sin altura. */
    public double volume() {
        return area() * height();
    }

    /** Centro del contorno (promedio ponderado por área); el promedio de esquinas si el área es casi nula. */
    public double[] centroid() {
        int n = corners.size();
        if (n == 0) return null;
        double a = signedArea();
        if (Math.abs(a) < 1e-9) {
            double sx = 0, sy = 0;
            for (double[] c : corners) {
                sx += c[0];
                sy += c[1];
            }
            return new double[]{sx / n, sy / n};
        }
        double cx = 0, cy = 0;
        for (int i = 0; i < n; i++) {
            double[] p = corners.get(i), q = corners.get((i + 1) % n);
            double w = p[0] * q[1] - q[0] * p[1];
            cx += (p[0] + q[0]) * w;
            cy += (p[1] + q[1]) * w;
        }
        return new double[]{cx / (6 * a), cy / (6 * a)};
    }

    /** Si un punto cae dentro del contorno (regla par-impar). */
    public boolean contains(double x, double y) {
        if (!closed()) return false;
        boolean in = false;
        for (int i = 0, n = corners.size(), j = n - 1; i < n; j = i++) {
            double[] a = corners.get(i), b = corners.get(j);
            if ((a[1] > y) != (b[1] > y) && x < (b[0] - a[0]) * (y - a[1]) / (b[1] - a[1]) + a[0]) in = !in;
        }
        return in;
    }

    /**
     * Si dos paredes se cruzan: pasa cuando las esquinas se marcaron en desorden, y entonces el área no vale. La app
     * avisa en vez de reordenarlas por su cuenta.
     */
    public boolean selfIntersects() {
        int n = corners.size();
        if (n < 4) return false;
        for (int i = 0; i < n; i++) {
            for (int j = i + 2; j < n; j++) {
                if (i == 0 && j == n - 1) continue; // paredes vecinas por el cierre
                if (cross(corners.get(i), corners.get((i + 1) % n), corners.get(j), corners.get((j + 1) % n))) return true;
            }
        }
        return false;
    }

    private static boolean cross(double[] a, double[] b, double[] c, double[] d) {
        double d1 = side(c, d, a), d2 = side(c, d, b), d3 = side(a, b, c), d4 = side(a, b, d);
        return ((d1 > 0 && d2 < 0) || (d1 < 0 && d2 > 0)) && ((d3 > 0 && d4 < 0) || (d3 < 0 && d4 > 0));
    }

    private static double side(double[] a, double[] b, double[] p) {
        return (b[0] - a[0]) * (p[1] - a[1]) - (b[1] - a[1]) * (p[0] - a[0]);
    }
}
