package com.josexato.navaja.measure.core;
import java.io.File;
import java.util.*;
public class HojaTracks {
    public static List<Sfm.Track> fromColmap(String z, File[] fs) throws Exception {
        Map<String, Integer> idx = new HashMap<>(); for (int i = 0; i < fs.length; i++) idx.put(fs[i].getName(), i);
        List<String> il = new ArrayList<>(); for (String l : java.nio.file.Files.readAllLines(java.nio.file.Paths.get(z + "/colmap/sparse/0/images.txt"))) if (!l.startsWith("#")) il.add(l);
        Map<Integer, Integer> imgFoto = new HashMap<>(); Map<Integer, String[]> imgObs = new HashMap<>();
        for (int k = 0; k + 1 < il.size(); k += 2) { String[] hd = il.get(k).split(" "); int id = Integer.parseInt(hd[0]); imgFoto.put(id, idx.get(hd[9])); imgObs.put(id, il.get(k + 1).trim().split(" ")); }
        List<Sfm.Track> tracks = new ArrayList<>();
        for (String l : java.nio.file.Files.readAllLines(java.nio.file.Paths.get(z + "/colmap/sparse/0/points3D.txt"))) {
            if (l.startsWith("#") || l.isEmpty()) continue; String[] f = l.split(" ");
            TreeMap<Integer, float[]> ob = new TreeMap<>();
            for (int k = 8; k + 1 < f.length; k += 2) { int im = Integer.parseInt(f[k]), pi = Integer.parseInt(f[k + 1]); String[] o = imgObs.get(im); Integer fo = imgFoto.get(im); if (fo == null) continue;
                ob.put(fo, new float[]{Float.parseFloat(o[3 * pi]) - 0.5f, Float.parseFloat(o[3 * pi + 1]) - 0.5f}); }
            if (ob.size() < 2) continue;
            int col = 0xFF000000 | (Integer.parseInt(f[4]) << 16) | (Integer.parseInt(f[5]) << 8) | Integer.parseInt(f[6]);
            int s0 = ob.firstKey(), s1 = ob.lastKey(); Sfm.Track t = null; float[] prev = null; int pf = -1;
            for (int fr = s0; fr <= s1; fr++) {
                float[] v = ob.get(fr);
                if (v == null) { Map.Entry<Integer, float[]> nx = ob.ceilingEntry(fr); double al = (double) (fr - pf) / (nx.getKey() - pf); v = new float[]{(float) (prev[0] + al * (nx.getValue()[0] - prev[0])), (float) (prev[1] + al * (nx.getValue()[1] - prev[1]))}; }
                else { prev = v; pf = fr; }
                if (t == null) t = new Sfm.Track(fr, v[0], v[1], col); else t.add(v[0], v[1]);
            }
            tracks.add(t);
        }
        return tracks;
    }
}
