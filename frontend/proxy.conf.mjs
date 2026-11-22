// Relais des appels /api vers le serveur Spring Boot pendant le développement.
const port = process.env.DEV_BACKEND_PORT || '8080';

export default {
  '/api': { target: `http://localhost:${port}`, secure: false },
};
