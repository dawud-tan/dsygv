*> \brief \b DLASCL multiplies a general rectangular matrix by a real scalar defined as cto/cfrom.
*
*  =========== DOCUMENTATION ===========
*
* Online html documentation available at
*            http://www.netlib.org/lapack/explore-html/
*
*> Download DLASCL + dependencies
*> <a href="http://www.netlib.org/cgi-bin/netlibfiles.tgz?format=tgz&filename=/lapack/lapack_routine/dlascl.f">
*> [TGZ]</a>
*> <a href="http://www.netlib.org/cgi-bin/netlibfiles.zip?format=zip&filename=/lapack/lapack_routine/dlascl.f">
*> [ZIP]</a>
*> <a href="http://www.netlib.org/cgi-bin/netlibfiles.txt?format=txt&filename=/lapack/lapack_routine/dlascl.f">
*> [TXT]</a>
*
*  Definition:
*  ===========
*
*       SUBROUTINE DLASCL( TYPE, KL, KU, CFROM, CTO, M, N, A, LDA, INFO )
*
*       .. Scalar Arguments ..
*       CHARACTER          TYPE
*       INTEGER            INFO, KL, KU, LDA, M, N
*       DOUBLE PRECISION   CFROM, CTO
*       ..
*       .. Array Arguments ..
*       DOUBLE PRECISION   A( LDA, * )
*       ..
*
*
*> \par Purpose:
*  =============
*>
*> \verbatim
*>
*> DLASCL multiplies the M by N real matrix A by the real scalar
*> CTO/CFROM.  This is done without over/underflow as long as the final
*> result CTO*A(I,J)/CFROM does not over/underflow. TYPE specifies that
*> A may be full, upper triangular, lower triangular, upper Hessenberg,
*> or banded.
*> \endverbatim
*
*  Arguments:
*  ==========
*
*> \param[in] TYPE
*> \verbatim
*>          TYPE is CHARACTER*1
*>          TYPE indices the storage type of the input matrix.
*>          = 'G':  A is a full matrix.
*>          = 'L':  A is a lower triangular matrix.
*>          = 'U':  A is an upper triangular matrix.
*>          = 'H':  A is an upper Hessenberg matrix.
*>          = 'B':  A is a symmetric band matrix with lower bandwidth KL
*>                  and upper bandwidth KU and with the only the lower
*>                  half stored.
*>          = 'Q':  A is a symmetric band matrix with lower bandwidth KL
*>                  and upper bandwidth KU and with the only the upper
*>                  half stored.
*>          = 'Z':  A is a band matrix with lower bandwidth KL and upper
*>                  bandwidth KU. See DGBTRF for storage details.
*> \endverbatim
*>
*> \param[in] KL
*> \verbatim
*>          KL is INTEGER
*>          The lower bandwidth of A.  Referenced only if TYPE = 'B',
*>          'Q' or 'Z'.
*> \endverbatim
*>
*> \param[in] KU
*> \verbatim
*>          KU is INTEGER
*>          The upper bandwidth of A.  Referenced only if TYPE = 'B',
*>          'Q' or 'Z'.
*> \endverbatim
*>
*> \param[in] CFROM
*> \verbatim
*>          CFROM is DOUBLE PRECISION
*> \endverbatim
*>
*> \param[in] CTO
*> \verbatim
*>          CTO is DOUBLE PRECISION
*>
*>          The matrix A is multiplied by CTO/CFROM. A(I,J) is computed
*>          without over/underflow if the final result CTO*A(I,J)/CFROM
*>          can be represented without over/underflow.  CFROM must be
*>          nonzero.
*> \endverbatim
*>
*> \param[in] M
*> \verbatim
*>          M is INTEGER
*>          The number of rows of the matrix A.  M >= 0.
*> \endverbatim
*>
*> \param[in] N
*> \verbatim
*>          N is INTEGER
*>          The number of columns of the matrix A.  N >= 0.
*> \endverbatim
*>
*> \param[in,out] A
*> \verbatim
*>          A is DOUBLE PRECISION array, dimension (LDA,N)
*>          The matrix to be multiplied by CTO/CFROM.  See TYPE for the
*>          storage type.
*> \endverbatim
*>
*> \param[in] LDA
*> \verbatim
*>          LDA is INTEGER
*>          The leading dimension of the array A.
*>          If TYPE = 'G', 'L', 'U', 'H', LDA >= max(1,M);
*>             TYPE = 'B', LDA >= KL+1;
*>             TYPE = 'Q', LDA >= KU+1;
*>             TYPE = 'Z', LDA >= 2*KL+KU+1.
*> \endverbatim
*>
*> \param[out] INFO
*> \verbatim
*>          INFO is INTEGER
*>          0  - successful exit
*>          <0 - if INFO = -i, the i-th argument had an illegal value.
*> \endverbatim
*
*  Authors:
*  ========
*
*> \author Univ. of Tennessee
*> \author Univ. of California Berkeley
*> \author Univ. of Colorado Denver
*> \author NAG Ltd.
*
*> \ingroup lascl
*
*  =====================================================================

      SUBROUTINE dlascl( TYPE, KL, KU, CFROM, CTO, M, N, A, LDA,
     $                   INFO )
*
*  -- LAPACK auxiliary routine --
*  -- LAPACK is a software package provided by Univ. of Tennessee,    --
*  -- Univ. of California Berkeley, Univ. of Colorado Denver and NAG Ltd..--
*
*     .. Scalar Arguments ..
      CHARACTER          TYPE
      INTEGER            INFO, KL, KU, LDA, M, N
      DOUBLE PRECISION   CFROM, CTO
*     ..
*     .. Array Arguments ..
      DOUBLE PRECISION   A( LDA, * )
*     ..
*
*  =====================================================================
*
*     .. Parameters ..
      DOUBLE PRECISION   ZERO, ONE
      parameter( zero = 0.0d0, one = 1.0d0 )
*     ..
*     .. Local Scalars ..
      LOGICAL            DONE
      INTEGER            I, ITYPE, J, K1, K2, K3, K4
      DOUBLE PRECISION   BIGNUM, CFROM1, CFROMC, CTO1, CTOC, MUL, SMLNUM
*     ..
*     .. External Functions ..
      LOGICAL            LSAME, DISNAN
      DOUBLE PRECISION   DLAMCH
      EXTERNAL           lsame, dlamch, disnan
*     ..
*     .. Intrinsic Functions ..
      INTRINSIC          abs, max, min
*     ..
*     .. External Subroutines ..
      EXTERNAL           xerbla
*     ..
*     .. Executable Statements ..
*
*     Test the input arguments
*
      info = 0
*
      IF( lsame( TYPE, 'G' ) ) then
         itype = 0
      ELSE IF( lsame( TYPE, 'L' ) ) then
         itype = 1
      ELSE IF( lsame( TYPE, 'U' ) ) then
         itype = 2
      ELSE IF( lsame( TYPE, 'H' ) ) then
         itype = 3
      ELSE IF( lsame( TYPE, 'B' ) ) then
         itype = 4
      ELSE IF( lsame( TYPE, 'Q' ) ) then
         itype = 5
      ELSE IF( lsame( TYPE, 'Z' ) ) then
         itype = 6
      ELSE
         itype = -1
      END IF
*
      IF( itype.EQ.-1 ) THEN
         info = -1
      ELSE IF( cfrom.EQ.zero .OR. disnan(cfrom) ) THEN
         info = -4
      ELSE IF( disnan(cto) ) THEN
         info = -5
      ELSE IF( m.LT.0 ) THEN
         info = -6
      ELSE IF( n.LT.0 .OR. ( itype.EQ.4 .AND. n.NE.m ) .OR.
     $         ( itype.EQ.5 .AND. n.NE.m ) ) THEN
         info = -7
      ELSE IF( itype.LE.3 .AND. lda.LT.max( 1, m ) ) THEN
         info = -9
      ELSE IF( itype.GE.4 ) THEN
         IF( kl.LT.0 .OR. kl.GT.max( m-1, 0 ) ) THEN
            info = -2
         ELSE IF( ku.LT.0 .OR. ku.GT.max( n-1, 0 ) .OR.
     $            ( ( itype.EQ.4 .OR. itype.EQ.5 ) .AND. kl.NE.ku ) )
     $             THEN
            info = -3
         ELSE IF( ( itype.EQ.4 .AND. lda.LT.kl+1 ) .OR.
     $            ( itype.EQ.5 .AND. lda.LT.ku+1 ) .OR.
     $            ( itype.EQ.6 .AND. lda.LT.2*kl+ku+1 ) ) THEN
            info = -9
         END IF
      END IF
*
      IF( info.NE.0 ) THEN
         CALL xerbla( 'DLASCL', -info )
         RETURN
      END IF
*
*     Quick return if possible
*
      IF( n.EQ.0 .OR. m.EQ.0 )
     $   RETURN
*
*     Get machine parameters
*
      smlnum = dlamch( 'S' )
      bignum = one / smlnum
*
      cfromc = cfrom
      ctoc = cto
*
   10 CONTINUE
      cfrom1 = cfromc*smlnum
      IF( cfrom1.EQ.cfromc ) THEN
!        CFROMC is an inf.  Multiply by a correctly signed zero for
!        finite CTOC, or a NaN if CTOC is infinite.
         mul = ctoc / cfromc
         done = .true.
         cto1 = ctoc
      ELSE
         cto1 = ctoc / bignum
         IF( cto1.EQ.ctoc ) THEN
!           CTOC is either 0 or an inf.  In both cases, CTOC itself
!           serves as the correct multiplication factor.
            mul = ctoc
            done = .true.
            cfromc = one
         ELSE IF( abs( cfrom1 ).GT.abs( ctoc ) .AND. ctoc.NE.zero ) THEN
            mul = smlnum
            done = .false.
            cfromc = cfrom1
         ELSE IF( abs( cto1 ).GT.abs( cfromc ) ) THEN
            mul = bignum
            done = .false.
            ctoc = cto1
         ELSE
            mul = ctoc / cfromc
            done = .true.
            IF (mul .EQ. one)
     $         RETURN
         END IF
      END IF
*
      IF( itype.EQ.0 ) THEN
*
*        Full matrix
*
         DO 30 j = 1, n
            DO 20 i = 1, m
               a( i, j ) = a( i, j )*mul
   20       CONTINUE
   30    CONTINUE
*
      ELSE IF( itype.EQ.1 ) THEN
*
*        Lower triangular matrix
*
         DO 50 j = 1, n
            DO 40 i = j, m
               a( i, j ) = a( i, j )*mul
   40       CONTINUE
   50    CONTINUE
*
      ELSE IF( itype.EQ.2 ) THEN
*
*        Upper triangular matrix
*
         DO 70 j = 1, n
            DO 60 i = 1, min( j, m )
               a( i, j ) = a( i, j )*mul
   60       CONTINUE
   70    CONTINUE
*
      ELSE IF( itype.EQ.3 ) THEN
*
*        Upper Hessenberg matrix
*
         DO 90 j = 1, n
            DO 80 i = 1, min( j+1, m )
               a( i, j ) = a( i, j )*mul
   80       CONTINUE
   90    CONTINUE
*
      ELSE IF( itype.EQ.4 ) THEN
*
*        Lower half of a symmetric band matrix
*
         k3 = kl + 1
         k4 = n + 1
         DO 110 j = 1, n
            DO 100 i = 1, min( k3, k4-j )
               a( i, j ) = a( i, j )*mul
  100       CONTINUE
  110    CONTINUE
*
      ELSE IF( itype.EQ.5 ) THEN
*
*        Upper half of a symmetric band matrix
*
         k1 = ku + 2
         k3 = ku + 1
         DO 130 j = 1, n
            DO 120 i = max( k1-j, 1 ), k3
               a( i, j ) = a( i, j )*mul
  120       CONTINUE
  130    CONTINUE
*
      ELSE IF( itype.EQ.6 ) THEN
*
*        Band matrix
*
         k1 = kl + ku + 2
         k2 = kl + 1
         k3 = 2*kl + ku + 1
         k4 = kl + ku + 1 + m
         DO 150 j = 1, n
            DO 140 i = max( k1-j, k2 ), min( k3, k4-j )
               a( i, j ) = a( i, j )*mul
  140       CONTINUE
  150    CONTINUE
*
      END IF
*
      IF( .NOT.done )
     $   GO TO 10
*
      RETURN
*
*     End of DLASCL
*

      END