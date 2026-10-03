#include "dsterf_port.cpp"
#include <cstdio>
#include <cstdlib>
#include <vector>

int main(int argc, char** argv) {
    if (argc != 3) { std::fprintf(stderr, "usage: %s infile outfile\n", argv[0]); return 2; }
    FILE* fin = std::fopen(argv[1], "rb");
    if (!fin) { std::perror("open infile"); return 2; }
    int n = 0;
    if (std::fread(&n, sizeof(int), 1, fin) != 1) { std::fclose(fin); return 2; }

    // 1-based storage: allocate n+1, index 0 unused, matching dsterf_cpp's
    // own convention exactly so the driver's array layout needs no
    // translation at the call boundary.
    std::vector<double> d(n + 1, 0.0), e(n + 1, 0.0);
    if (n > 0 && std::fread(d.data() + 1, sizeof(double), n, fin) != (size_t)n) { std::fclose(fin); return 2; }
    if (n > 1 && std::fread(e.data() + 1, sizeof(double), n - 1, fin) != (size_t)(n - 1)) { std::fclose(fin); return 2; }
    std::fclose(fin);

    int info = 0;
    try {
        dsterf_port::dsterf_cpp(n, d.data(), e.data(), &info);
    } catch (const std::exception& ex) {
        std::fprintf(stderr, "dsterf_cpp threw: %s\n", ex.what());
        return 3;
    }

    FILE* fout = std::fopen(argv[2], "wb");
    if (!fout) { std::perror("open outfile"); return 2; }
    std::fwrite(&info, sizeof(int), 1, fout);
    if (n > 0) std::fwrite(d.data() + 1, sizeof(double), n, fout);
    std::fclose(fout);
    return 0;
}
