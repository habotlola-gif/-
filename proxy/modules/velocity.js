// Velocity: ослабляет отдачу, которую сервер присылает игроку (пакет set_entity_motion).
// horizontal / vertical — процент от исходной отдачи (100 = без изменений, 0 = полностью убрать).
// Ограничение: если сервер сам считает движение и «поправляет» позицию (server-authoritative
// movement), эффект может быть слабее или пропадать.
module.exports = {
  name: 'velocity',

  onClientbound (ctx, name, params) {
    if (name !== 'set_entity_motion') return
    if (params.runtime_entity_id !== ctx.runtimeId) return // только своя отдача
    const h = (ctx.settings.horizontal ?? 100) / 100
    const v = (ctx.settings.vertical ?? 100) / 100
    params.velocity.x *= h
    params.velocity.z *= h
    params.velocity.y *= v
  }
}
