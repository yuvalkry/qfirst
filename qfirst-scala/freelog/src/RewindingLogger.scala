package freelog

trait RewindingLogger[F[_], Msg] extends EphemeralLogger[F, Msg] {
  /** Save a new checkpoint. */
  def save: F[Unit]

  /** Restore to the last checkpoint, deleting logs and the checkpoint.
    * Can delete logs lazily, i.e., wait until the next log op to do so.
    */
  def restore: F[Unit]

  /** Commit the last checkpoint, keeping logs and folding the last two checkpoints together. */
  def commit: F[Unit]

  /** Flush a pending restore, i.e., force lazy deletes through. */
  def flush: F[Unit]

  /** Restore to last checkpoint without deleting it. */
  def rewind: F[Unit]// = restore >> save

  def block[A](fa: F[A]): F[A]// = save >> fa <* commit
}
