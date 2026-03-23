// Double sliding paraboloid background subtraction
// General-purpose cleanup for uneven illumination
run("Subtract Background...", "rolling=50 sliding stack");
run("Subtract Background...", "rolling=15 sliding stack");
