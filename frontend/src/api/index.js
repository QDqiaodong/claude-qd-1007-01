import axios from 'axios'

const http = axios.create({ baseURL: '/api', timeout: 20000 })

http.interceptors.response.use(
  (response) => response,
  (error) => {
    const message = error?.response?.data?.message || error?.message || '请求失败'
    return Promise.reject(new Error(message))
  }
)

async function call(promise) {
  const response = await promise
  const body = response.data
  if (!body || body.ok === false) {
    throw new Error((body && body.message) || '请求失败')
  }
  return body.data
}

const params = (o) => ({ params: o })

export default {
  // ---------------- 游乐设施 ----------------
  listRides: (p) => call(http.get('/rides', params(p))),
  createRide: (d) => call(http.post('/rides', d)),
  updateRide: (id, d) => call(http.put(`/rides/${id}`, d)),
  transferRide: (id, target) =>
    call(http.post(`/rides/${id}/transfer`, null, params({ target }))),
  boardRide: (id, heightCm, age, memberId) =>
    call(http.post(`/rides/${id}/board`, null, params({ heightCm, age, memberId }))),

  // ---------------- 检修安全复核与放行 ----------------
  listInspections: (rideId) => call(http.get(`/rides/${rideId}/inspections`)),
  activeInspection: (rideId) => call(http.get(`/rides/${rideId}/inspections/active`)),
  ensureInspection: (rideId) => call(http.post(`/rides/${rideId}/inspections`)),
  saveInspectionItems: (id, d) => call(http.put(`/inspections/${id}/items`, d)),
  submitInspection: (id, operator) =>
    call(http.post(`/inspections/${id}/submit`, null, params({ operator }))),
  releaseInspection: (id, operator) =>
    call(http.post(`/inspections/${id}/release`, null, params({ operator }))),

  // ---------------- 园区演出 ----------------
  listShows: () => call(http.get('/shows')),
  createShow: (d) => call(http.post('/shows', d)),
  updateShow: (id, d) => call(http.put(`/shows/${id}`, d)),
  reserveShow: (id, seats) =>
    call(http.post(`/shows/${id}/reserve`, null, params({ seats }))),

  // ---------------- 餐饮网点 ----------------
  listFnb: (p) => call(http.get('/fnb', params(p))),
  createFnb: (d) => call(http.post('/fnb', d)),
  updateFnb: (id, d) => call(http.put(`/fnb/${id}`, d)),
  transferFnb: (id, target) =>
    call(http.post(`/fnb/${id}/transfer`, null, params({ target }))),

  // ---------------- 会员 ----------------
  listMembers: (p) => call(http.get('/members', params(p))),
  createMember: (d) => call(http.post('/members', d)),

  // ---------------- 年卡 / 季卡 ----------------
  listSeasonPasses: () => call(http.get('/season-passes')),
  issueSeasonPass: (memberId, d) => call(http.post('/season-passes', d, params({ memberId }))),
  renewSeasonPass: (memberId, d) => call(http.put(`/season-passes/${memberId}`, d))
}
