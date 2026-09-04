# Build stage
FROM node:20-alpine AS build
WORKDIR /app

COPY package.json package-lock.json* ./
RUN npm install

COPY . .
RUN npm run build

# Runtime stage — serve with nginx
FROM nginx:alpine

# Custom nginx config for SPA routing
COPY --from=build /app/dist /usr/share/nginx/html

# Serve frontend on port 80
EXPOSE 80

CMD ["nginx", "-g", "daemon off;"]
