// Drives the PRODUCTION lapack_port::dsterf (inside phase1_solver.cpp),
// not the standalone intermediate port, through the same binary protocol
// the Fortran reference driver uses.
#include <cstdio>
#include <cstdlib>
#include <cstdarg>
#include <vector>
#include <span>
extern "C" int __android_log_print(int, const char*, const char*, ...) { return 0; }
#include "/WIN_D/protek/dsygv/app/src/main/cpp/phase1_solver.cpp"

int main(int argc, char** argv) {
    if (argc != 3) return 2;
    FILE* fin = std::fopen(argv[1], "rb");
    if (!fin) return 2;
    int n = 0;
    if (std::fread(&n, sizeof(int), 1, fin) != 1) { std::fclose(fin); return 2; }
    std::vector<double> d(n > 0 ? n : 1, 0.0), e(n > 1 ? n - 1 : 1, 0.0);   // 0-based
    if (n > 0 && std::fread(d.data(), sizeof(double), n, fin) != (size_t)n) { std::fclose(fin); return 2; }
    if (n > 1 && std::fread(e.data(), sizeof(double), n-1, fin) != (size_t)(n-1)) { std::fclose(fin); return 2; }
    std::fclose(fin);
    int info = 0;
    lapack_port::dsterf(std::span<double>(d).first(n > 0 ? n : 0),
                        std::span<double>(e).first(n > 1 ? n - 1 : 0), &info);
    FILE* fout = std::fopen(argv[2], "wb");
    if (!fout) return 2;
    std::fwrite(&info, sizeof(int), 1, fout);
    if (n > 0) std::fwrite(d.data(), sizeof(double), n, fout);
    std::fclose(fout);
    return 0;
}
