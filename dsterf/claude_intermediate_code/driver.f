      PROGRAM DSTERF_DRIVER
*     Reads N (4-byte int), then N doubles (D), then N-1 doubles (E)
*     from unit 10 (binary, stream access) -- calls the real DSTERF,
*     writes INFO (4-byte int) then N doubles (updated D) to unit 11,
*     raw binary, so comparison against the C++ port is exact bit
*     comparison, never a decimal-text round trip.
      IMPLICIT NONE
      INTEGER N, INFO, I
      DOUBLE PRECISION D(100000), E(100000)
      CHARACTER*256 INFILE, OUTFILE

      CALL GET_COMMAND_ARGUMENT(1, INFILE)
      CALL GET_COMMAND_ARGUMENT(2, OUTFILE)

      OPEN(UNIT=10, FILE=INFILE, FORM='UNFORMATTED', ACCESS='STREAM',
     $     STATUS='OLD')
      READ(10) N
      READ(10) (D(I), I=1,N)
      IF (N.GT.1) READ(10) (E(I), I=1,N-1)
      CLOSE(10)

      CALL DSTERF(N, D, E, INFO)

      OPEN(UNIT=11, FILE=OUTFILE, FORM='UNFORMATTED', ACCESS='STREAM',
     $     STATUS='REPLACE')
      WRITE(11) INFO
      WRITE(11) (D(I), I=1,N)
      CLOSE(11)

      END
