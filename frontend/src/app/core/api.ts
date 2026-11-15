import { HttpClient, HttpErrorResponse, HttpResponse } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';

/** Erreur renvoyée par l'API : `message` est le texte envoyé par le serveur, à montrer tel quel. */
export class ApiError extends Error {
  constructor(public status: number, message: string) {
    super(message);
  }
}

async function toApiError(e: unknown): Promise<never> {
  if (e instanceof HttpErrorResponse) {
    let body = e.error;
    if (body instanceof Blob) body = await body.text();
    if (body && typeof body === 'object') body = body.error ?? body.message ?? '';
    const fallback = e.status === 0 ? 'Serveur injoignable.' : `Erreur ${e.status}`;
    throw new ApiError(e.status, (typeof body === 'string' && body.trim()) || fallback);
  }
  throw e;
}

/** Appels à l'API sous forme de promesses. Les erreurs sont des ApiError. */
@Injectable({ providedIn: 'root' })
export class Api {
  private http = inject(HttpClient);

  get<T = any>(url: string): Promise<T> {
    return firstValueFrom(this.http.get<T>(url)).catch(toApiError);
  }
  post<T = any>(url: string, body: unknown = {}): Promise<T> {
    return firstValueFrom(this.http.post<T>(url, body)).catch(toApiError);
  }
  put<T = any>(url: string, body: unknown = {}): Promise<T> {
    return firstValueFrom(this.http.put<T>(url, body)).catch(toApiError);
  }
  patch<T = any>(url: string, body: unknown = {}): Promise<T> {
    return firstValueFrom(this.http.patch<T>(url, body)).catch(toApiError);
  }
  delete<T = any>(url: string): Promise<T> {
    return firstValueFrom(this.http.delete<T>(url)).catch(toApiError);
  }
  /** POST dont la réponse est un fichier (PDF, export) : donne accès au contenu et aux en-têtes. */
  postForBlob(url: string, body: unknown): Promise<HttpResponse<Blob>> {
    return firstValueFrom(this.http.post(url, body, { responseType: 'blob', observe: 'response' })).catch(toApiError);
  }
  getBlob(url: string): Promise<HttpResponse<Blob>> {
    return firstValueFrom(this.http.get(url, { responseType: 'blob', observe: 'response' })).catch(toApiError);
  }
}
