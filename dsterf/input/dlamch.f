*> \brief \b DLAMCH
*
*  =========== DOCUMENTATION ===========
*
* Online html documentation available at
*            http://www.netlib.org/lapack/explore-html/
*
*  Definition:
*  ===========
*
*      DOUBLE PRECISION FUNCTION DLAMCH( CMACH )
*
*     .. Scalar Arguments ..
*     CHARACTER          CMACH
*     ..
*
*
*> \par Purpose:
*  =============
*>
*> \verbatim
*>
*> DLAMCH determines double precision machine parameters.
*> \endverbatim
*
*  Arguments:
*  ==========
*
*> \param[in] CMACH
*> \verbatim
*>          CMACH is CHARACTER*1
*>          Specifies the value to be returned by DLAMCH:
*>          = 'E' or 'e',   DLAMCH := eps
*>          = 'S' or 's ,   DLAMCH := sfmin
*>          = 'B' or 'b',   DLAMCH := base
*>          = 'P' or 'p',   DLAMCH := eps*base
*>          = 'N' or 'n',   DLAMCH := t
*>          = 'R' or 'r',   DLAMCH := rnd
*>          = 'M' or 'm',   DLAMCH := emin
*>          = 'U' or 'u',   DLAMCH := rmin
*>          = 'L' or 'l',   DLAMCH := emax
*>          = 'O' or 'o',   DLAMCH := rmax
*>          where
*>          eps   = relative machine precision
*>          sfmin = safe minimum, such that 1/sfmin does not overflow
*>          base  = base of the machine
*>          prec  = eps*base
*>          t     = number of (base) digits in the mantissa
*>          rnd   = 1.0 when rounding occurs in addition, 0.0 otherwise
*>          emin  = minimum exponent before (gradual) underflow
*>          rmin  = underflow threshold - base**(emin-1)
*>          emax  = largest exponent before overflow
*>          rmax  = overflow threshold  - (base**emax)*(1-eps)
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

*> \ingroup lamch
*
*  =====================================================================

      DOUBLE PRECISION FUNCTION dlamch( CMACH )
*
*  -- LAPACK auxiliary routine --
*  -- LAPACK is a software package provided by Univ. of Tennessee,    --
*  -- Univ. of California Berkeley, Univ. of Colorado Denver and NAG Ltd..--
*
*     .. Scalar Arguments ..
      CHARACTER          cmach
*     ..
*
* =====================================================================
*
*     .. Parameters ..
      DOUBLE PRECISION   one, zero
      parameter( one = 1.0d+0, zero = 0.0d+0 )
*     ..
*     .. Local Scalars ..
      DOUBLE PRECISION   rnd, eps, sfmin, small, rmach
*     ..
*     .. External Functions ..
      LOGICAL            lsame
      EXTERNAL           lsame
*     ..
*     .. Intrinsic Functions ..
      INTRINSIC          digits, epsilon, huge, maxexponent,
     $                   minexponent, radix, tiny
*     ..
*     .. Executable Statements ..
*
*
*     Assume rounding, not chopping. Always.
*
      rnd = one
*
      IF( one.EQ.rnd ) THEN
         eps = epsilon(zero) * 0.5
      ELSE
         eps = epsilon(zero)
      END IF
*
      IF( lsame( cmach, 'E' ) ) THEN
         rmach = eps
      ELSE IF( lsame( cmach, 'S' ) ) THEN
         sfmin = tiny(zero)
         small = one / huge(zero)
         IF( small.GE.sfmin ) THEN
*
*           Use SMALL plus a bit, to avoid the possibility of rounding
*           causing overflow when computing  1/sfmin.
*
            sfmin = small*( one+eps )
         END IF
         rmach = sfmin
      ELSE IF( lsame( cmach, 'B' ) ) THEN
         rmach = radix(zero)
      ELSE IF( lsame( cmach, 'P' ) ) THEN
         rmach = eps * radix(zero)
      ELSE IF( lsame( cmach, 'N' ) ) THEN
         rmach = digits(zero)
      ELSE IF( lsame( cmach, 'R' ) ) THEN
         rmach = rnd
      ELSE IF( lsame( cmach, 'M' ) ) THEN
         rmach = minexponent(zero)
      ELSE IF( lsame( cmach, 'U' ) ) THEN
         rmach = tiny(zero)
      ELSE IF( lsame( cmach, 'L' ) ) THEN
         rmach = maxexponent(zero)
      ELSE IF( lsame( cmach, 'O' ) ) THEN
         rmach = huge(zero)
      ELSE
         rmach = zero
      END IF
*
      dlamch = rmach
      RETURN
*
*     End of DLAMCH
*

      END

************************************************************************
*> \brief \b DLAMC3
*> \details
*> \b Purpose:
*> \verbatim
*> DLAMC3  is intended to force  A  and  B  to be stored prior to doing
*> the addition of  A  and  B ,  for use in situations where optimizers
*> might hold one of these in a register.
*> \endverbatim
*> \author LAPACK is a software package provided by Univ. of Tennessee, Univ. of California Berkeley, Univ. of Colorado Denver and NAG Ltd..
*> \param[in] A
*> \verbatim
*>          A is a DOUBLE PRECISION
*> \endverbatim
*>
*> \param[in] B
*> \verbatim
*>          B is a DOUBLE PRECISION
*>          The values A and B.
*> \endverbatim
*>
*> \ingroup lamc3
*>

      DOUBLE PRECISION FUNCTION dlamc3( A, B )
*
*  -- LAPACK auxiliary routine --
*     Univ. of Tennessee, Univ. of California Berkeley and NAG Ltd..
*
*     .. Scalar Arguments ..
      DOUBLE PRECISION   a, b
*     ..
* =====================================================================
*
*     .. Executable Statements ..
*
      dlamc3 = a + b
*
      RETURN
*
*     End of DLAMC3
*

      END
*
************************************************************************